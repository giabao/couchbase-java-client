/**
 * Copyright (C) 2009-2013 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package com.couchbase.client;

import com.couchbase.client.vbucket.ConfigurationException;
import com.couchbase.client.vbucket.ConfigurationProvider;
import com.couchbase.client.vbucket.ConfigurationProviderHTTP;
import com.couchbase.client.vbucket.CouchbaseNodeOrder;
import com.couchbase.client.vbucket.Reconfigurable;
import com.couchbase.client.vbucket.VBucketNodeLocator;
import com.couchbase.client.vbucket.config.Bucket;
import com.couchbase.client.vbucket.config.Config;
import com.couchbase.client.vbucket.config.ConfigType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.DefaultHashAlgorithm;
import net.spy.memcached.FailureMode;
import net.spy.memcached.HashAlgorithm;
import net.spy.memcached.KetamaNodeLocator;
import net.spy.memcached.MemcachedConnection;
import net.spy.memcached.MemcachedNode;
import net.spy.memcached.NodeLocator;
import net.spy.memcached.auth.AuthDescriptor;
import net.spy.memcached.auth.PlainCallbackHandler;

/**
 * Couchbase implementation of ConnectionFactory.
 *
 * <p>
 * This implementation creates connections where the operation queue is an
 * ArrayBlockingQueue and the read and write queues are unbounded
 * LinkedBlockingQueues. The <code>Retry</code> FailureMode and <code>
 * KetamaHash</code> VBucket hashing mechanism are always used. If other
 * configurations are needed, look at the ConnectionFactoryBuilder.
 *
 * </p>
 */
public class CouchbaseConnectionFactory extends BinaryConnectionFactory {

  /**
   * Default failure mode.
   */
  public static final FailureMode DEFAULT_FAILURE_MODE =
    FailureMode.Redistribute;

  /**
   * Default hash algorithm.
   */
  public static final HashAlgorithm DEFAULT_HASH =
    DefaultHashAlgorithm.NATIVE_HASH;

  /**
   * Maximum length of the operation queue returned by this connection factory.
   */
  public static final int DEFAULT_OP_QUEUE_LEN = 16384;
  /**
   * Specify a default minimum reconnect interval of 1.1s.
   *
   * This means that if a reconnect is needed, it won't try to reconnect
   * more frequently than 1.1s between tries.  The initial HTTP connections
   * under us take up to 500ms per request.
   */
  public static final long DEFAULT_MIN_RECONNECT_INTERVAL = 1100;

  /**
   * Default View request timeout in ms.
   */
  public static final int DEFAULT_VIEW_TIMEOUT = 75000;

  /**
   * Default size of view io worker threads.
   */
  public static final int DEFAULT_VIEW_WORKER_SIZE = 1;

  /**
   * Default amount of max connections per node.
   */
  public static final int DEFAULT_VIEW_CONNS_PER_NODE = 10;

  /**
   * Default Timeout when persistence/replication constraints are used (in ms).
   */
  public static final long DEFAULT_OBS_TIMEOUT = 5000;

  /**
   * Default Observe poll interval in ms.
   */
  public static final long DEFAULT_OBS_POLL_INTERVAL = 10;

  /**
   * Default maximum amount of poll cycles before failure.
   *
   * See {@link #DEFAULT_OBS_TIMEOUT} for correct use. The number of polls is
   * now calculated automatically based on the {@link #DEFAULT_OBS_TIMEOUT} and
   * {@link #DEFAULT_OBS_POLL_INTERVAL}.
   */
  @Deprecated
  public static final int DEFAULT_OBS_POLL_MAX = 500;

  /**
   * Default Node ordering to use for streaming connection.
   */
  public static final CouchbaseNodeOrder DEFAULT_STREAMING_NODE_ORDER =
    CouchbaseNodeOrder.RANDOM;

  protected volatile ConfigurationProvider configurationProvider;
  private volatile String bucket;
  private volatile String pass;
  private volatile List<URI> storedBaseList;
  private static final Logger LOGGER =
    Logger.getLogger(CouchbaseConnectionFactory.class.getName());
  private volatile boolean needsReconnect;
  private final AtomicBoolean doingResubscribe = new AtomicBoolean(false);
  private volatile long thresholdLastCheck = System.nanoTime();
  private final AtomicInteger configThresholdCount = new AtomicInteger(0);
  private final int maxConfigCheck = 10; //maximum allowed checks before we
                                         // reconnect in a 10 sec interval
  private volatile long configProviderLastUpdateTimestamp;
  private long minReconnectInterval = DEFAULT_MIN_RECONNECT_INTERVAL;
  private final ExecutorService resubExec = Executors.newSingleThreadExecutor();
  private final CouchbaseNodeOrder nodeOrder = DEFAULT_STREAMING_NODE_ORDER;
  private ClusterManager clusterManager;

  /**
   * Create a new {@link CouchbaseConnectionFactory} and load the required
   * connection information from system properties.
   *
   * <p>The following properties need to be set in order to bootstrap:
   *  - cbclient.nodes: ;-separated list of URIs
   *  - cbclient.bucket: name of the bucket
   *  - cbclient.password: password of the bucket
   * </p>
   */
  public CouchbaseConnectionFactory() {
    String nodes = CouchbaseProperties.getProperty("nodes");
    String bucket =  CouchbaseProperties.getProperty("bucket");
    String password = CouchbaseProperties.getProperty("password");

    if (nodes == null) {
      throw new IllegalArgumentException("System property cbclient.nodes "
        + "not set or empty");
    }
    if (bucket == null) {
      throw new IllegalArgumentException("System property cbclient.bucket "
        + "not set or empty");
    }
    if (password == null) {
      throw new IllegalArgumentException("System property cbclient.password "
        + "not set or empty");
    }

    List<URI> baseList = new ArrayList<URI>();
    String[] nodeList = nodes.split(";");
    for (String node : nodeList) {
      try {
        baseList.add(new URI(node));
      } catch (Exception e) {
        throw new IllegalArgumentException("Could not parse node list into "
          + " URI format.");
      }
    }

    initialize(baseList, bucket, password);
  }

  public CouchbaseConnectionFactory(final List<URI> baseList,
    final String bucketName, String password) throws IOException {
    initialize(baseList, bucketName, password);
  }

  private void initialize(List<URI> baseList, String bucket, String password) {
    potentiallyRandomizeNodeList(baseList);

    storedBaseList = new ArrayList<URI>();
    for (URI bu : baseList) {
      if (!bu.isAbsolute()) {
        throw new IllegalArgumentException("The base URI must be absolute");
      }
      storedBaseList.add(bu);
    }

    if (bucket == null || bucket.isEmpty()) {
      throw new IllegalArgumentException("The bucket name must not be null "
        + "or empty.");
    }
    if (password == null) {
      throw new IllegalArgumentException("The bucket password must not be "
        + " null.");
    }

    this.bucket = bucket;
    pass = password;
    configurationProvider =
      new ConfigurationProviderHTTP(baseList, bucket, password);
  }

  @Override
  public MemcachedConnection createConnection(List<InetSocketAddress> addrs)
    throws IOException {
    Config config = getVBucketConfig();
    if (config.getConfigType() == ConfigType.MEMCACHE) {
      return new CouchbaseMemcachedConnection(getReadBufSize(), this, addrs,
        getInitialObservers(), getFailureMode(), getOperationFactory());
    } else if (config.getConfigType() == ConfigType.COUCHBASE) {
      return new CouchbaseConnection(getReadBufSize(), this, addrs,
        getInitialObservers(), getFailureMode(), getOperationFactory());
    }
    throw new IOException("No ConnectionFactory for bucket type "
      + config.getConfigType());
  }


  public ViewConnection createViewConnection(
      List<InetSocketAddress> addrs) throws IOException {
    return new ViewConnection(this, addrs, bucket, pass);
  }

  @Override
  public NodeLocator createLocator(List<MemcachedNode> nodes) {
    Config config = getVBucketConfig();

    if (config == null) {
      throw new IllegalStateException("Couldn't get config");
    }

    if (config.getConfigType() == ConfigType.MEMCACHE) {
      return new KetamaNodeLocator(nodes, DefaultHashAlgorithm.KETAMA_HASH);
    } else if (config.getConfigType() == ConfigType.COUCHBASE) {
      return new VBucketNodeLocator(nodes, getVBucketConfig());
    } else {
      throw new IllegalStateException("Unhandled locator type: "
          + config.getConfigType());
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see net.spy.memcached.ConnectionFactory#shouldOptimize()
   */
  @Override
  public boolean shouldOptimize() {
    return false;
  }

  @Override
  public AuthDescriptor getAuthDescriptor() {
    if (!configurationProvider.getAnonymousAuthBucket().equals(bucket)
        && bucket != null) {
      return new AuthDescriptor(new String[] {},
        new PlainCallbackHandler(bucket, pass));
    } else {
      return null;
    }
  }

  public String getBucketName() {
    return bucket;
  }

  public int getViewTimeout() {
    return DEFAULT_VIEW_TIMEOUT;
  }

  public int getViewWorkerSize() {
    return DEFAULT_VIEW_WORKER_SIZE;
  }

  public int getViewConnsPerNode() {
    return DEFAULT_VIEW_CONNS_PER_NODE;
  }

  public CouchbaseNodeOrder getStreamingNodeOrder() {
    return nodeOrder;
  }

  public Config getVBucketConfig() {
    Bucket config = configurationProvider.getBucketConfiguration(bucket);
    if(config == null) {
      throw new ConfigurationException("Could not fetch valid configuration "
        + "from provided nodes. Stopping.");
    } else if (config.isNotUpdating()) {
      LOGGER.warning("Noticed bucket configuration to be disconnected, "
        + "will attempt to reconnect");
      setConfigurationProvider(new ConfigurationProviderHTTP(storedBaseList,
        bucket, pass));
    }
    return configurationProvider.getBucketConfiguration(bucket).getConfig();
  }

  public synchronized ConfigurationProvider getConfigurationProvider() {
    return configurationProvider;
  }

  protected void requestConfigReconnect(String bucketName, Reconfigurable rec) {
    configurationProvider.markForResubscribe(bucketName, rec);
    needsReconnect = true;
  }

  synchronized void setConfigurationProvider(
    ConfigurationProvider configProvider) {
    this.configProviderLastUpdateTimestamp = System.currentTimeMillis();
    this.configurationProvider = configProvider;
  }

  void setMinReconnectInterval(long reconnIntervalMsecs) {
    this.minReconnectInterval = reconnIntervalMsecs;
  }

  /**
   * Check if a configuration update is needed.
   *
   * There are two main reasons that can trigger a configuration update. Either
   * there is a configuration update happening in the cluster, or operations
   * added to the queue can not find their corresponding node. For the latter,
   * see the {@link #pastReconnThreshold()} method for further details.
   *
   * If a configuration update is needed, a resubscription for configuration
   * updates is triggered. Note that since reconnection takes some time,
   * the method will also wait a time period given by
   * {@link #getMinReconnectInterval()} before the resubscription is triggered.
   */
  void checkConfigUpdate() {
    if (needsReconnect || pastReconnThreshold()) {
      long now = System.currentTimeMillis();
      long intervalWaited = now - this.configProviderLastUpdateTimestamp;
      if (intervalWaited < this.getMinReconnectInterval()) {
        LOGGER.log(Level.FINE, "Ignoring config update check. Only {0}ms out"
                + " of a threshold of {1}ms since last update.",
                new Object[]{intervalWaited, this.getMinReconnectInterval()});
        return;
      }

      if (doingResubscribe.compareAndSet(false, true)) {
        resubConfigUpdate();
      } else {
        LOGGER.log(Level.CONFIG, "Duplicate resubscribe for config updates"
          + " suppressed.");
      }
    } else {
      LOGGER.log(Level.FINE, "No reconnect required, though check requested."
              + " Current config check is {0} out of a threshold of {1}.",
              new Object[]{configThresholdCount, maxConfigCheck});
    }
  }

  /**
   * Resubscribe for configuration updates.
   */
  private synchronized void resubConfigUpdate() {
    LOGGER.log(Level.INFO, "Attempting to resubscribe for cluster config"
      + " updates.");
    resubExec.execute(new Resubscriber());
  }

  /**
   * Checks if there have been more requests than allowed through
   * maxConfigCheck in a 10 second period.
   *
   * If this is the case, then true is returned. If the timeframe between
   * two distinct requests is more than 10 seconds, a fresh timeframe starts.
   * This means that 10 calls every second would trigger an update while
   * 1 operation, then a 11 second sleep and one more operation would not.
   *
   * @return true if there were more config check requests than maxConfigCheck
   *              in the 10 second period.
   */
  protected boolean pastReconnThreshold() {
    long currentTime = System.nanoTime();

    if (currentTime - thresholdLastCheck >= TimeUnit.SECONDS.toNanos(10)) {
      configThresholdCount.set(0);
    }

    thresholdLastCheck = currentTime;
    if (configThresholdCount.incrementAndGet() >= maxConfigCheck) {
      return true;
    }

    return false;
  }

  /**
   * The minimum reconnect interval in milliseconds.
   *
   * @return the minReconnectInterval
   */
  public long getMinReconnectInterval() {
    return minReconnectInterval;
  }

  /**
   * The observe poll interval in milliseconds.
   *
   * @return the observe poll interval.
   */
  public long getObsPollInterval() {
    return DEFAULT_OBS_POLL_INTERVAL;
  }

  /**
   * The observe timeout in milliseconds.
   *
   * @return the observe timeout.
   */
  public long getObsTimeout() {
    return DEFAULT_OBS_TIMEOUT;
  }

  /**
   * The number of observe polls to execute before giving up.
   *
   * It is calculated out of the observe timeout and the observe interval,
   * rounded to the next largest integer value.
   *
   * @return the number of polls.
   */
  public int getObsPollMax() {
    return new Double(
      Math.ceil((double) getObsTimeout() / getObsPollInterval())
    ).intValue();
  }

  /**
   * Returns the amount of how many config checks in a given time period
   * (currently 10 seconds) are allowed before a reconfiguration is triggered.
   *
   * @return the number of config checks allowed.
   */
  int getMaxConfigCheck() {
    return maxConfigCheck;
  }

  private class Resubscriber implements Runnable {

    public void run() {
      String threadNameBase = "Couchbase/Resubscriber (Status: ";
      Thread.currentThread().setName(threadNameBase + "running)");
      LOGGER.log(Level.CONFIG, "Resubscribing for {0} using base list {1}",
        new Object[]{bucket, storedBaseList});

      long reconnectAttempt = 0;
      long backoffTime = 1000;
      long maxWaitTime = 10000;
      do {
        try {
          long waitTime = (reconnectAttempt++)*backoffTime;
          if(reconnectAttempt >= 10) {
            waitTime = maxWaitTime;
          }
          LOGGER.log(Level.INFO, "Reconnect attempt {0}, waiting {1}ms",
            new Object[]{reconnectAttempt, waitTime});
          Thread.sleep(waitTime);

          ConfigurationProvider oldConfigProvider = getConfigurationProvider();
          Reconfigurable oldRec = oldConfigProvider.getReconfigurable();

          ConfigurationProvider newConfigProvider =
            new ConfigurationProviderHTTP(storedBaseList, bucket, pass);
          newConfigProvider.subscribe(bucket, oldRec);

          setConfigurationProvider(newConfigProvider);
          oldConfigProvider.shutdown();

          if (!doingResubscribe.compareAndSet(true, false)) {
            LOGGER.log(Level.WARNING,
              "Could not reset from doing a resubscribe.");
          }
        } catch (Exception ex) {
          LOGGER.log(Level.WARNING,
            "Resubscribe attempt failed: ", ex);
        }
      } while(doingResubscribe.get());

      Thread.currentThread().setName(threadNameBase + "complete)");
    }
  }

  /**
   * Returns a ClusterManager and initializes one if it does not exist.
   * @return Returns an instance of a ClusterManager.
   */
  public ClusterManager getClusterManager() {
    if(clusterManager == null) {
      clusterManager = new ClusterManager(storedBaseList, bucket, pass);
    }
    return clusterManager;
  }

  /**
   * Updates the stored base list with a new one based on the config.
   *
   * @param config
   */
  public void updateStoredBaseList(Config config) {
    List<String> bucketServers = config.getRestEndpoints();
    if (bucketServers.size() > 0) {
      List<URI> newList = new ArrayList<URI>();
      for (String bucketServer : bucketServers) {
        try {
          newList.add(new URI(bucketServer));
        } catch(URISyntaxException ex) {
          getLogger().warn("Could not add node to updated bucket list because "
            + "of a parsing exception.");
          getLogger().debug("Could not parse list because: " + ex);
        }
      }

      if (nodeListsAreDifferent(storedBaseList, newList)) {
        getLogger().info("Replacing current streaming node list "
          + storedBaseList + " with " + newList);
        potentiallyRandomizeNodeList(newList);
        storedBaseList = newList;
        getConfigurationProvider().updateBaseListFromConfig(newList);
      }
    }
  }

  /**
   * Returns the current base list.
   *
   * @return the base list.
   */
  List<URI> getStoredBaseList() {
    return storedBaseList;
  }

  /**
   * Randomizes the entries of the node list if needed.
   *
   * @param list the list to potentially randomize.
   */
  private void potentiallyRandomizeNodeList(List<URI> list) {
    if (getStreamingNodeOrder().equals(CouchbaseNodeOrder.ORDERED)) {
      return;
    }

    Collections.shuffle(list);
  }

  /**
   * Check if two given node lists are different.
   *
   * @param left one node list
   * @param right the other node list
   * @return true if they are different, false otherwise.
   */
  private boolean nodeListsAreDifferent(List<URI> left, List<URI> right) {
    if (left.size() != right.size()) {
      return true;
    }

    for (URI uri : left) {
      if (!right.contains(uri)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("CouchbaseConnectionFactory{");
    sb.append(", bucket='").append(getBucketName()).append('\'');
    sb.append(", nodes=").append(getStoredBaseList());
    sb.append(", order=").append(getStreamingNodeOrder());
    sb.append(", opTimeout=").append(getOperationTimeout());
    sb.append(", opQueue=").append(getOpQueueLen());
    sb.append(", opQueueBlockTime=").append(getOpQueueMaxBlockTime());
    sb.append(", obsPollInt=").append(getObsPollInterval());
    sb.append(", obsPollMax=").append(getObsPollMax());
    sb.append(", obsTimeout=").append(getObsTimeout());
    sb.append(", viewConns=").append(getViewConnsPerNode());
    sb.append(", viewTimeout=").append(getViewTimeout());
    sb.append(", viewWorkers=").append(getViewWorkerSize());
    sb.append(", configCheck=").append(getMaxConfigCheck());
    sb.append(", reconnectInt=").append(getMinReconnectInterval());
    sb.append(", failureMode=").append(getFailureMode());
    sb.append(", hashAlgo=").append(getHashAlg());
    sb.append('}');
    return sb.toString();
  }
}
