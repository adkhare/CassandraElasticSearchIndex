package com.bettercloud.cassandra;

/**
 * Created by amit on 5/28/15.
 */

import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.*;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.FBUtilities;
import org.mapdb.Atomic;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;




public class BetterCloud implements IEndpointStateChangeSubscriber, BetterCloudMBean {
    protected static final Logger logger = LoggerFactory.getLogger(BetterCloud.class);
    private final static BetterCloud INSTANCE = new BetterCloud();
    static {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.registerMBean(INSTANCE, new ObjectName(MBEAN_NAME));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static final String COMMIT_LOGS = "commit-logs";
    public static Exception constructionException;
    IndexEventSubscriber indexEventSubscriber;
    Atomic.Long writes;
    IndexingService indexingService;

    DB db;
    BlockingQueue<IndexEntryEvent> queue;
    JedisPoolConfig jedisPoolConfig;
    JedisPool jedisPool;
    JedisCluster jedisCluster;
    Set<HostAndPort> jedisNodes;

    private BetterCloud() {
        try {
            queue = getQueue();
            writes = getAtomicLong(COMMIT_LOGS + "writes");
            indexingService = new IndexingService(getAtomicLong(COMMIT_LOGS + "reads"));
            indexEventSubscriber = new IndexEventSubscriber(indexingService, queue);
            jedisPoolConfig = new JedisPoolConfig();
            jedisPoolConfig.setLifo(false);
            jedisPoolConfig.setMaxTotal(10);
            jedisPoolConfig.setMaxWaitMillis(10);
            jedisNodes = new HashSet<HostAndPort>();
            jedisNodes.add(new HostAndPort("10.0.0.4",6379));
            jedisNodes.add(new HostAndPort("10.0.0.9",6379));
            jedisNodes.add(new HostAndPort("10.0.0.10",6379));
            jedisCluster = new JedisCluster(jedisNodes,jedisPoolConfig);
            Gossiper.instance.register(this);
        } catch (IOException e) {
            constructionException = e;
        }
    }

    public Atomic.Long getAtomicLong(String name) {
        if (db.exists(name)) return db.getAtomicLong(name);
        return db.createAtomicLong(name, 0l);
    }

    public static BetterCloud getInstance() {
        if (constructionException != null) throw new RuntimeException(constructionException);
        return INSTANCE;
    }


    public void register(RowIndexSupport rowIndexSupport) {
        this.indexingService.register(rowIndexSupport);
        if (StorageService.instance.isInitialized()) {
           //indexingService.updateIndexers(rowIndexSupport);
           if (!indexEventSubscriber.started.get()) indexEventSubscriber.start();
        }
    }

    public Jedis getRedisConnectionFromPool(){
        return jedisPool.getResource();
    }

    public JedisCluster getRedisClusterConnectionFromPool(){
        return jedisCluster;
    }

    public void returnRedisResource(JedisCluster jedis) {

    }

    public long publish(ByteBuffer rowKey, ColumnFamily columnFamily) {
        /*try {
            indexingService.support.get(columnFamily.metadata().cfName).indexRow(rowKey, columnFamily);
        } catch (Exception e) {
            logger.error("",e);
        }
        return -1;
        */
        if(rowKey!=null){
            queue.offer(new IndexEntryEvent(IndexEntryEvent.Type.UPSERT, rowKey, columnFamily));
            long writeGen = writes.incrementAndGet();
            if (logger.isDebugEnabled())
                logger.debug("Write gen:" + writeGen);
            return writeGen;
        }else{
            throw new NullPointerException();
        }
    }


    public void catchUp(long latest) {
        while (true) {
            if (indexingService.reads.get() >= latest) break;
        }
    }

    private BlockingQueue<IndexEntryEvent> getQueue() throws IOException {
        db = getDB();
        BlockingQueue<IndexEntryEvent> queue;
        if (db.exists(COMMIT_LOGS)) {
            queue = db.getQueue(COMMIT_LOGS);
        } else {
            queue = db.createQueue(COMMIT_LOGS, new IndexEntryEvent.IndexEntryEventSerializer(), false);
        }
        return queue;

    }

    private DB getDB() throws IOException {
        String dirName = null;
        try {
            dirName = BetterCloudUtil.getDataDirs()[0];
        } catch (ConfigurationException e) {
            e.printStackTrace();
        }
            dirName = dirName + File.separator + COMMIT_LOGS;
            File dir = new File(dirName);
            boolean createNew = false;
            if (!dir.exists()) {
                createNew = dir.mkdirs();
            }
            logger.warn("##### Index commit log directory path #####[" + dir.getPath() + "]. Created new [" + createNew + "]");
            File file = new File(dir, "log");
            if (!file.exists()) {
                createNew = file.createNewFile();
            }
            logger.warn("##### Index commit log file path #####[" + file.getPath() + "]. Created new [" + createNew + "]");
            return DBMaker.
                    newFileDB(file)
                    .mmapFileEnableIfSupported()
                    .closeOnJvmShutdown()
                    .make();
    }


    @Override
    public void onJoin(InetAddress endpoint, EndpointState epState) {
    }

    @Override
    public void beforeChange(InetAddress endpoint, EndpointState currentState, ApplicationState newStateKey, VersionedValue newValue) {
    }

    @Override
    public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value) {
        if (state == ApplicationState.TOKENS && FBUtilities.getBroadcastAddress().equals(endpoint)) {
            //indexingService.updateAllIndexers();
            if (!indexEventSubscriber.started.get()) indexEventSubscriber.start();
        }
    }

    @Override
    public void onAlive(InetAddress endpoint, EndpointState state) {
    }

    @Override
    public void onDead(InetAddress endpoint, EndpointState state) {
        if (FBUtilities.getBroadcastAddress().equals(endpoint))
            indexEventSubscriber.stopped.set(true);
    }

    @Override
    public void onRemove(InetAddress endpoint) {
    }

    @Override
    public void onRestart(InetAddress endpoint, EndpointState state) {
    }


    @Override
    public String[] allIndexes() {
        String[] allIndexes = new String[indexingService.support.size()];
        int i = 0;
        for (Map.Entry<String, RowIndexSupport> entry : indexingService.support.entrySet()) {
            RowIndexSupport rowIndexSupport = entry.getValue();
            allIndexes[i++] = rowIndexSupport.indexName;
        }
        return allIndexes;
    }

    @Override
    public String[] indexShards(String indexName) {
        /*RowIndexSupport indexSupport = getRowIndexSupportByIndexName(indexName);
        if (indexSupport != null) {
            Set<Range<Token>> indexShards = indexSupport.indexContainer.indexers.keySet();
            String[] indexRanges = new String[indexShards.size()];
            int i = 0;
            for (Range<Token> indexRange : indexShards) {
                indexRanges[i++] = indexRange.toString();
            }
            return indexRanges;
        }*/
        return null;
    }

    @Override
    public String describeIndex(String indexName) throws IOException {
        /*RowIndexSupport indexSupport = getRowIndexSupportByIndexName(indexName);
        if (indexSupport != null) {
            return indexSupport.getOptions().describeAsJson();
        }*/
        return null;
    }

    private RowIndexSupport getRowIndexSupportByIndexName(String indexName) {
        /*for (Map.Entry<String, RowIndexSupport> entry : indexingService.support.entrySet()) {
            RowIndexSupport rowIndexSupport = entry.getValue();
            if (rowIndexSupport.indexContainer.indexName.equalsIgnoreCase(indexName)) return rowIndexSupport;
        }*/
        return null;
    }

    @Override
    public long indexLiveSize(String indexName) {
        /*RowIndexSupport indexSupport = getRowIndexSupportByIndexName(indexName);
        if (indexSupport != null) {
            return indexSupport.indexContainer.liveSize();
        }*/
        return 0;
    }

    @Override
    public long indexSize(String indexName) {
        /*RowIndexSupport indexSupport = getRowIndexSupportByIndexName(indexName);
        if (indexSupport != null) {
            return indexSupport.indexContainer.size();
        }*/
        return 0;
    }

    @Override
    public long writeGeneration() {
        return writes.get();
    }

    @Override
    public long readGeneration() {
        return indexingService.reads.get();
    }


}
