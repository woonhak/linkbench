package com.facebook.LinkBench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.facebook.LinkBench.LinkStore.LinkStoreOp;

/*
 * TODO: update to reflect changes
 * Muli-threaded loader for loading data into hbase.
 * The range from startid1 to maxid1 is chunked up into equal sized
 * disjoint ranges so that each loader can work on its range.
 * The #links generated for an id1 is random but based on probablities
 * given in nlinks_choices and odds_in_billion. The actual counts of
 * #links generated is tracked in nlinks_counts.
 */

public class LinkBenchLoad implements Runnable {

  private final Logger logger = Logger.getLogger(ConfigUtil.LINKBENCH_LOGGER); 
  
  private long randomid2max; // whether id2 should be generated randomly

  private long maxid1;   // max id1 to generate
  private long startid1; // id1 at which to start
  private int  loaderID; // ID for this loader
  private LinkStore store;// store interface (several possible implementations
                          // like mysql, hbase etc)
  private int datasize; // 'data' column size for one (id1, type, id2)
  private LinkBenchStats stats;
  private LinkBenchLatency latencyStats;
  long displayfreq;
  int maxsamples;

  Level debuglevel;
  String dbid;

  int nlinks_func; // distribution function for #links
  int nlinks_config; // any additional config to go with above the above func
  int nlinks_default; // default value of #links - expected to be 0 or 1

  
  // Counters for load statistics
  long sameShuffle;
  long diffShuffle;
  long linksloaded;
  
  /** 
   * special case for single hot row benchmark. If singleAssoc is set, 
   * then make this method not print any statistics message, all statistics
   * are collected at a higher layer. */
  boolean singleAssoc;

  private BlockingQueue<LoadChunk> chunk_q;

  private LoadProgress prog_tracker;


  /**
   * Convenience constructor
   * @param store2
   * @param props
   * @param latencyStats
   * @param loaderID
   * @param nloaders
   */
  public LinkBenchLoad(LinkStore store, Properties props,
      LinkBenchLatency latencyStats, int loaderID, boolean singleAssoc,
      int nloaders, LoadProgress prog_tracker, Random rng) {
    this(store, props, latencyStats, loaderID, singleAssoc,
              new ArrayBlockingQueue<LoadChunk>(2), prog_tracker);
    
    // Just add a single chunk to the queue
    chunk_q.add(new LoadChunk(loaderID, startid1, maxid1, rng));
    chunk_q.add(LoadChunk.SHUTDOWN);
  }

  
  public LinkBenchLoad(LinkStore store,
                       Properties props,
                       LinkBenchLatency latencyStats,
                       int loaderID,
                       boolean singleAssoc,
                       BlockingQueue<LoadChunk> chunk_q,
                       LoadProgress prog_tracker) throws LinkBenchConfigError {
    /*
     * Initialize fields from arguments
     */
    this.store = store;
    this.latencyStats = latencyStats;
    this.loaderID = loaderID;
    this.singleAssoc = singleAssoc;
    this.chunk_q = chunk_q;
    this.prog_tracker = prog_tracker;
    
    
    /*
     * Load settings from properties
     */
    maxid1 = Long.parseLong(props.getProperty(Config.MAX_ID));
    startid1 = Long.parseLong(props.getProperty(Config.MIN_ID));

    // math functions may cause problems for id1 = 0. Start at 1.
    if (startid1 <= 0) {
      startid1 = 1;
    }

    debuglevel = ConfigUtil.getDebugLevel(props);
    datasize = Integer.parseInt(props.getProperty(Config.LINK_DATASIZE));

    nlinks_func = Integer.parseInt(props.getProperty(Config.NLINKS_FUNC));
    nlinks_config = Integer.parseInt(props.getProperty(Config.NLINKS_CONFIG));
    nlinks_default = Integer.parseInt(props.getProperty(Config.NLINKS_DEFAULT));
    if (nlinks_func == -2) {//real distribution has it own initialization
      try {
        //load statistical data into RealDistribution
        RealDistribution.loadOneShot(props);
      } catch (Exception e) {
        logger.error(e);
        System.exit(1);
      }
    }

    displayfreq = Long.parseLong(props.getProperty(Config.DISPLAY_FREQ));
    maxsamples = Integer.parseInt(props.getProperty(Config.MAX_STAT_SAMPLES));
    
    dbid = props.getProperty(Config.DBID);
    

    /*
     * Initialize statistics
     */
    linksloaded = 0;
    sameShuffle = 0;
    diffShuffle = 0;
    stats = new LinkBenchStats(loaderID, displayfreq, maxsamples);
    
    randomid2max = Long.parseLong(props.getProperty(Config.RANDOM_ID2_MAX));
  }

  public long getLinksLoaded() {
    return linksloaded;
  }

  // Gets the #links to generate for an id1 based on distribution specified
  // by nlinks_func, nlinks_config and nlinks_default.
  public static long getNlinks(Random rng,
                               long id1, long startid1, long maxid1,
                               int nlinks_func, int nlinks_config,
                               int nlinks_default) {
    long nlinks = nlinks_default; // start with nlinks_default
    long temp;

    switch(nlinks_func) {
    case -3 :
      return nlinks;
    case -2 :
      // real distribution
      nlinks = RealDistribution.getNlinks(rng, id1, startid1, maxid1);
      break;

    case -1 :
      // Corresponds to function 1/x
      nlinks += (long)Math.ceil((double)maxid1/(double)id1);
      break;
    case 0 :
      // if id1 is multiple of nlinks_config, then add nlinks_config
      nlinks += (id1 % nlinks_config == 0 ? nlinks_config : 0);
      break;

    case 100 :
      // Corresponds to exponential distribution
      // If id1 is nlinks_config^k, then add
      // nlinks_config^k - nlinks_config^(k-1) more links
      long log = (long)Math.ceil(Math.log(id1)/Math.log(nlinks_config));
      temp = (long)Math.pow(nlinks_config, log);
      nlinks += (temp == id1 ?
                 (id1 - (long)Math.pow(nlinks_config, log - 1)) :
                 0);
      break;

    default:
      // if nlinks_func is 2 then
      //   if id1 is K * K, then add K * K - (K - 1) * (K - 1) more links.
      //   The idea is to give more #links to perfect squares. The larger
      //   the perfect square is, the more #links it will get.
      // Generalize the above for nlinks_func is n:
      //   if id1 is K^n, then add K^n - (K - 1)^n more links
      long nthroot = (long)Math.ceil(Math.pow(id1, (1.0)/nlinks_func));
      temp = (long)Math.pow(nthroot, nlinks_func);
      nlinks += (temp == id1 ?
                (id1 - (long)Math.pow(nthroot - 1, nlinks_func)) :
                0);
      break;
    }

    return nlinks;
  }

  @Override
  public void run() {

    int bulkLoadBatchSize = store.bulkLoadBatchSize();
    boolean bulkLoad = bulkLoadBatchSize > 0;
    ArrayList<Link> loadBuffer = null;
    ArrayList<LinkCount> countLoadBuffer = null;
    if (bulkLoad) {
      loadBuffer = new ArrayList<Link>(bulkLoadBatchSize);
      countLoadBuffer = new ArrayList<LinkCount>(bulkLoadBatchSize);
    }

    logger.info("Starting loader thread  #" + loaderID);
    
    while (true) {
      LoadChunk chunk;
      try {
        chunk = chunk_q.take();
      } catch (InterruptedException ie) {
        logger.warn("InterruptedException not expected, try again", ie);
        continue;
      }
      
      // Shutdown signal is received though special chunk type
      if (chunk.shutdown) {
        break;
      }

      // Load the link range specified in the chunk
      processChunk(chunk, bulkLoad, bulkLoadBatchSize,
                    loadBuffer, countLoadBuffer);
    }
    
    if (bulkLoad) {
      // Load any remaining links or counts
      loadLinks(loadBuffer);
      loadCounts(countLoadBuffer);
    }
    
    if (!singleAssoc) {
      logger.info(" Same shuffle = " + sameShuffle +
                         " Different shuffle = " + diffShuffle);
      if (bulkLoad) {
        stats.displayStats(Arrays.asList(LinkStoreOp.LOAD_LINKS_BULK,
            LinkStoreOp.LOAD_COUNTS_BULK, LinkStoreOp.LOAD_LINKS_BULK_NLINKS,
            LinkStoreOp.LOAD_COUNTS_BULK_NLINKS));
      } else {
        stats.displayStats(Arrays.asList(LinkStoreOp.LOAD_LINK));
      }
    }
    
    store.close();
  }

  private void processChunk(LoadChunk chunk, boolean bulkLoad,
      int bulkLoadBatchSize, ArrayList<Link> loadBuffer,
      ArrayList<LinkCount> countLoadBuffer) {
    if (Level.DEBUG.isGreaterOrEqual(debuglevel)) {
      logger.debug("Loader thread  #" + loaderID + " processing "
                  + chunk.toString());
    }
    
    // Counter for total number of links loaded in chunk;
    long links_in_chunk = 0;

    Link link = null;
    if (!bulkLoad) {
      // When bulk-loading, need to have multiple link objects at a time
      // otherwise reuse object
      link = initLink();
    }
    
    long prevPercentPrinted = 0;
    for (long i = chunk.start; i < chunk.end; i += chunk.step) {
      long nlinks = 0;
      long id1;
      if (nlinks_func == -2) {
        long res[] = RealDistribution.getId1AndNLinks(chunk.rng, i, 
                                            startid1, maxid1);
        id1 = res[0];
        nlinks = res[1];
        if (id1 == i) {
          sameShuffle++;
        } else {
          diffShuffle++;
        }
      } else {
        id1 = i;
        nlinks = getNlinks(chunk.rng, id1, startid1, maxid1,
                  nlinks_func, nlinks_config, nlinks_default);
      }
      
      links_in_chunk += nlinks;
 
      if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
        logger.trace("i = " + i + " id1 = " + id1 +
                           " nlinks = " + nlinks);
      }
 
      createOutLinks(chunk.rng, link, loadBuffer, countLoadBuffer,
          id1, nlinks, singleAssoc, bulkLoad, bulkLoadBatchSize);
 
      if (!singleAssoc) {
        long nloaded = (i - chunk.start) / chunk.step;
        if (bulkLoad) {
          nloaded -= loadBuffer.size();
        }
        long percent = 100 * nloaded/(chunk.size);
        if ((percent % 10 == 0) && (percent > prevPercentPrinted)) {
          logger.debug(chunk.toString() +  ": Percent done = " + percent);
          prevPercentPrinted = percent;
        }
      }
    }
    
    // Update progress and maybe print message
    prog_tracker.update(chunk.size, links_in_chunk);
  }

  /**
   * Create the out links for a given id1
   * @param link
   * @param loadBuffer
   * @param id1
   * @param nlinks
   * @param singleAssoc
   * @param bulkLoad
   * @param bulkLoadBatchSize
   */
  private void createOutLinks(Random rng,
      Link link, ArrayList<Link> loadBuffer,
      ArrayList<LinkCount> countLoadBuffer,
      long id1, long nlinks, boolean singleAssoc, boolean bulkLoad,
      int bulkLoadBatchSize) {
    Map<Long, LinkCount> linkTypeCounts = null;
    if (bulkLoad) {
      linkTypeCounts = new HashMap<Long, LinkCount>();
    }
    
    for (long j = 0; j < nlinks; j++) {
      if (bulkLoad) {
        // Can't reuse link object
        link = initLink();
      }
      constructLink(rng, link, id1, j, singleAssoc);
      
      if (bulkLoad) {
        loadBuffer.add(link);
        if (loadBuffer.size() >= bulkLoadBatchSize) {
          loadLinks(loadBuffer);
        }
        
        // Update link counts for this type
        LinkCount count = linkTypeCounts.get(link.link_type); 
        if (count == null) {
          count = new LinkCount(id1,link.id1_type, link.link_type,
                                link.time, link.version, 1);
          linkTypeCounts.put(link.link_type, count);
        } else {
          count.count++;
          count.time = link.time;
          count.version = link.version;
        }
      } else {
        loadLink(link, j, nlinks, singleAssoc);
      }
    }
    
    // Maintain the counts separately
    if (bulkLoad) {
      for (LinkCount count: linkTypeCounts.values()) {
        countLoadBuffer.add(count);
        if (countLoadBuffer.size() >= bulkLoadBatchSize) {
          loadCounts(countLoadBuffer);
        }
      }
    }
  }

  private Link initLink() {
    Link link = new Link();
    link.link_type = LinkStore.LINK_TYPE;
    link.id1_type = LinkStore.ID1_TYPE;
    link.id2_type = LinkStore.ID2_TYPE;
    link.visibility = LinkStore.VISIBILITY_DEFAULT;
    link.version = 0;
    link.data = new byte[datasize];
    link.time = System.currentTimeMillis();
    return link;
  }

  /**
   * Helper method to fill in link data
   * @param link this link is filled in.  Should have been initialized with
   *            initLink() earlier
   * @param outlink_ix the number of this link out of all outlinks from
   *                    id1
   * @param singleAssoc whether we are in singleAssoc mode
   */
  private void constructLink(Random rng, Link link, long id1,
      long outlink_ix, boolean singleAssoc) {
    link.id1 = id1;

    // Using random number generator for id2 means we won't know
    // which id2s exist. So link id1 to
    // maxid1 + id1 + 1 thru maxid1 + id1 + nlinks(id1) UNLESS
    // config randomid2max is nonzero.
    if (singleAssoc) {
      link.id2 = 45; // some constant
    } else {
      link.id2 = (randomid2max == 0 ?
                 (maxid1 + id1 + outlink_ix) :
                 rng.nextInt((int)randomid2max));
      link.time = System.currentTimeMillis();
      // generate data as a sequence of random characters from 'a' to 'd'
      link.data = new byte[datasize];
      for (int k = 0; k < datasize; k++) {
        link.data[k] = (byte)('a' + Math.abs(rng.nextInt(4)));
      }
    }

    if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
      logger.trace("id2 chosen is " + link.id2);
    }
    
    link.time = System.currentTimeMillis();
  }

  /**
   * Load an individual link into the db.
   * 
   * If an error occurs during loading, this method will log it,
   *  add stats, and reset the connection.
   * @param link
   * @param outlink_ix
   * @param nlinks
   * @param singleAssoc
   */
  private void loadLink(Link link, long outlink_ix, long nlinks,
      boolean singleAssoc) {
    long timestart = 0;
    if (!singleAssoc) {
      timestart = System.nanoTime();
    }
    
    try {
      // no inverses for now
      store.addLink(dbid, link, true);
      linksloaded++;
  
      if (!singleAssoc && outlink_ix == nlinks - 1) {
        long timetaken = (System.nanoTime() - timestart);
  
        // convert to microseconds
        stats.addStats(LinkStoreOp.LOAD_LINK, timetaken/1000, false);
  
        latencyStats.recordLatency(loaderID, 
                      LinkStoreOp.LOAD_LINK, timetaken);
      }
  
    } catch (Throwable e){//Catch exception if any
        long endtime2 = System.nanoTime();
        long timetaken2 = (endtime2 - timestart)/1000;
        logger.error("Error: " + e.getMessage(), e);
        stats.addStats(LinkStoreOp.LOAD_LINK, timetaken2, true);
        store.clearErrors(loaderID);
    }
  }

  private void loadLinks(ArrayList<Link> loadBuffer) {
    long timestart = System.nanoTime();
    
    try {
      // no inverses for now
      int nlinks = loadBuffer.size();
      store.addBulkLinks(dbid, loadBuffer, true);
      linksloaded += nlinks;
      loadBuffer.clear();
  
      long timetaken = (System.nanoTime() - timestart);
  
      // convert to microseconds
      stats.addStats(LinkStoreOp.LOAD_LINKS_BULK, timetaken/1000, false);
      stats.addStats(LinkStoreOp.LOAD_LINKS_BULK_NLINKS, nlinks, false);
  
      latencyStats.recordLatency(loaderID, LinkStoreOp.LOAD_LINKS_BULK,
                                                             timetaken);
    } catch (Throwable e){//Catch exception if any
        long endtime2 = System.nanoTime();
        long timetaken2 = (endtime2 - timestart)/1000;
        logger.error("Error: " + e.getMessage(), e);
        stats.addStats(LinkStoreOp.LOAD_LINKS_BULK, timetaken2, true);
        store.clearErrors(loaderID);
    }
  }
  
  private void loadCounts(ArrayList<LinkCount> loadBuffer) {
    long timestart = System.nanoTime();
    
    try {
      // no inverses for now
      int ncounts = loadBuffer.size();
      store.addBulkCounts(dbid, loadBuffer);
      loadBuffer.clear();
  
      long timetaken = (System.nanoTime() - timestart);
  
      // convert to microseconds
      stats.addStats(LinkStoreOp.LOAD_COUNTS_BULK, timetaken/1000, false);
      stats.addStats(LinkStoreOp.LOAD_COUNTS_BULK_NLINKS, ncounts, false);
  
      latencyStats.recordLatency(loaderID, LinkStoreOp.LOAD_COUNTS_BULK,
                                                             timetaken);
    } catch (Throwable e){//Catch exception if any
        long endtime2 = System.nanoTime();
        long timetaken2 = (endtime2 - timestart)/1000;
        logger.error("Error: " + e.getMessage(), e);
        stats.addStats(LinkStoreOp.LOAD_COUNTS_BULK, timetaken2, true);
        store.clearErrors(loaderID);
    }
  }
  
  /**
   * Represents a portion of the id space, starting with
   * start, going up until end (non-inclusive) with step size
   * step
   *
   */
  public static class LoadChunk {
    public static LoadChunk SHUTDOWN = new LoadChunk(true,
                                              0, 0, 0, 1, null);

    public LoadChunk(long id, long start, long end, Random rng) {
      this(false, id, start, end, 1, rng);
    }
    public LoadChunk(boolean shutdown,
                      long id, long start, long end, long step, Random rng) {
      super();
      this.shutdown = shutdown;
      this.id = id;
      this.start = start;
      this.end = end;
      this.step = step;
      this.size = (end - start) / step;
      this.rng = rng;
    }
    public final boolean shutdown;
    public final long id;
    public final long start;
    public final long end;
    public final long step;
    public final long size;
    public Random rng;
    
    public String toString() {
      if (shutdown) {
        return "chunk SHUTDOWN";
      }
      String range;
      if (step == 1) {
        range = "[" + start + ":" + end + "]";
      } else {
        range = "[" + start + ":" + step + ":" + end + "]";
      }
      return "chunk " + id + range;
    }
  }
  public static class LoadProgress {
    // TODO: hardcoded
    private static final long PROGRESS_REPORT_INTERVAL = 50000;
    
    public LoadProgress(Logger progressLogger, long id1s_total) {
      super();
      this.progressLogger = progressLogger;
      this.id1s_total = id1s_total;
      this.starttime_ms = 0;
      this.id1s_loaded = new AtomicLong();
      this.links_loaded = new AtomicLong();
    }
    private final Logger progressLogger;
    private final AtomicLong id1s_loaded; // progress
    private final AtomicLong links_loaded; // progress
    private final long id1s_total; // goal
    private long starttime_ms;
    
    /** Mark current time as start time for load */
    public void startTimer() {
      starttime_ms = System.currentTimeMillis();
    }
    
    /**
     * Update progress
     * @param id1_incr number of additional id1s loaded since last call
     * @param links_incr number of links loaded since last call
     */
    public void update(long id1_incr, long links_incr) {
      long curr_id1s = id1s_loaded.addAndGet(id1_incr);
      
      long curr_links = links_loaded.addAndGet(links_incr);
      long prev_links = curr_links - links_incr;
      
      if ((curr_links / PROGRESS_REPORT_INTERVAL) >
          (prev_links / PROGRESS_REPORT_INTERVAL) || curr_id1s == id1s_total) {
        double percentage = (curr_id1s / (double)id1s_total) * 100.0;

        // Links per second loaded
        long now = System.currentTimeMillis();
        double link_rate = ((curr_links) / ((double) now - starttime_ms))*1000;
        double id1_rate = ((curr_id1s) / ((double) now - starttime_ms))*1000;
        progressLogger.info(String.format(
            "%d/%d id1s loaded (%.1f%% complete) at %.2f id1s/sec avg. " +
            "%d links loaded at %.2f links/sec avg.", 
            curr_id1s, id1s_total, percentage, id1_rate,
            curr_links, link_rate));
      }
    }
  }
}
