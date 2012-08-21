package com.facebook.LinkBench;

/**
 * Consolidate shared config key strings in this file
 * See sample config file for documentation of config properties
 * @author tarmstrong
 *
 */
public class Config {

  public static final String DEBUGLEVEL = "debuglevel";
  
  /* Control store implementations used */
  public static final String LINKSTORE_CLASS = "linkstore";
  public static final String NODESTORE_CLASS = "nodestore";
  
  /* Schema and tables used */
  public static final String DBID = "dbid";
  public static final String LINK_TABLE = "linktable";
  public static final String COUNT_TABLE = "counttable";
  public static final String NODE_TABLE = "nodetable";
  
  /* Control graph structure */
  public static final String LOAD_RANDOM_SEED = "load_random_seed";
  public static final String MIN_ID = "startid1";
  public static final String MAX_ID = "maxid1";
  public static final String RANDOM_ID2_MAX = "randomid2max";
  public static final String NLINKS_FUNC = "nlinks_func";
  public static final String NLINKS_CONFIG = "nlinks_config";
  public static final String NLINKS_DEFAULT = "nlinks_default";
  public static final String LINK_DATASIZE = "datasize";

  /* Loading performance tuning */
  public static final String NUM_LOADERS = "loaders";
  public static final String LOADER_CHUNK_SIZE = "loader_chunk_size";
  
  /* Request workload */
  public static final String NUM_REQUESTERS = "requesters";
  public static final String REQUEST_RANDOM_SEED = "request_random_seed";
  public static final String READ_CONFIG = "read_config";
  public static final String READ_FUNCTION = "read_function";
  public static final String WRITE_CONFIG = "write_config";
  public static final String WRITE_FUNCTION = "write_function";
  public static final String PR_ADD_LINK = "addlink";
  public static final String PR_DELETE_LINK = "deletelink";
  public static final String PR_UPDATE_LINK = "updatelink";
  public static final String PR_COUNT_LINKS = "countlink";
  public static final String PR_GET_LINK = "getlink";
  public static final String PR_GET_LINK_LIST = "getlinklist";
  public static final String PR_ADD_NODE = "addnode";
  public static final String PR_UPDATE_NODE = "updatenode";
  public static final String PR_DELETE_NODE = "deletenode";
  public static final String PR_GET_NODE = "getnode";
  public static final String MAX_TIME = "maxtime";
  public static final String REQUEST_RATE = "requestrate";
  public static final String NUM_REQUESTS = "requests";
  public static final String ID2GEN_CONFIG = "id2gen_config";
  
  /* Statistics collection and reporting */
  public static final String MAX_STAT_SAMPLES = "maxsamples";
  public static final String DISPLAY_FREQ = "displayfreq";
  public static final String MAPRED_REPORT_PROGRESS = "reportprogress";
  public static final String PROGRESS_FREQ = "progressfreq";
      
  /* MapReduce specific configuration */
  public static final String TEMPDIR = "tempdir";
  public static final String LOAD_DATA = "loaddata";
  public static final String MAPRED_USE_INPUT_FILES = "useinputfiles";

  /* External data */
  public static final String DISTRIBUTION_DATA_FILE = "data_file";
}