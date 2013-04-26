package com.continuuity.data.operation.ttqueue.randomized;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.data.engine.memory.MemoryOVCTable;
import com.continuuity.data.operation.executor.omid.TransactionOracle;
import com.continuuity.data.operation.ttqueue.TTQueue;
import com.continuuity.data.operation.ttqueue.TTQueueNewOnVCTable;
import com.continuuity.data.runtime.DataFabricModules;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 *
 */
public class TTQueueRandomizedTest {
  private static final Injector injector = Guice.createInjector(new DataFabricModules().getInMemoryModules());
  private static final TransactionOracle oracle = injector.getInstance(TransactionOracle.class);

  private static final Logger LOG = LoggerFactory.getLogger(TTQueueRandomizedTest.class);

  public static final String HASH_KEY = "hash_key";
  private static final int NUM_THREADS = 50;

  //@Test
  public void runRandomizedTest() throws Exception {
    for(int i=0; i<30; ++i) {
      LOG.info(String.format("**************************** Run %d started *************************************", i));
      testDriver();
      LOG.info(String.format("**************************** Run %d done *************************************", i));
    }
  }

  public void testDriver() throws Exception {
    CConfiguration cConfiguration = new CConfiguration();
    cConfiguration.setLong(TTQueueNewOnVCTable.TTQUEUE_EVICT_INTERVAL_SECS, 5);
    //cConfiguration.setInt(TTQueueNewOnVCTable.TTQUEUE_MAX_CRASH_DEQUEUE_TRIES, 4);
    // TODO: delete queue data in the end
    TTQueue ttQueue = createQueue(cConfiguration);

    // Create a random configuration object
    TestConfig testConfig = new TestConfig(new RandomSelectionFunction());
//    TestConfig testConfig = new TestConfig(new DeterministicSelectorFunction());
    final int numProducers = testConfig.getNumProducers();
    LOG.info("Num producers=" + numProducers);
    final int numConsumerGroups = testConfig.getNumConsumerGroups();
    LOG.info("Num consumer groups=" + numConsumerGroups);
    LOG.info("Num threads=" + NUM_THREADS);
    ListeningExecutorService listeningExecutorService =
      MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(NUM_THREADS));
    TestController testController = new TestController();

    // Create entries that need to be enqueued
    ImmutableList.Builder<Integer> builder = ImmutableList.builder();
    final int numEnqueues = testConfig.getNumberEnqueues();
    LOG.info("Num enqueues=" + numEnqueues);
    for(int i = 0; i < numEnqueues; ++i) {
      builder.add(i);
    }
    List<Integer> inputList = builder.build();

    // Create producers
    List<ListenableFuture<?>> producerFutures = Lists.newArrayList();
    Queue<Integer> inputQueue = new ConcurrentLinkedQueue<Integer>(inputList);
    Map<Integer, List<Integer>> enqueuesMap = Maps.newConcurrentMap();
    Map<Integer, List<Integer>> invalidMap = Maps.newConcurrentMap();
    for(int i = 0; i < numProducers; ++i) {
      enqueuesMap.put(i, Lists.<Integer>newArrayList());
      invalidMap.put(i, Lists.<Integer>newArrayList());
      ListenableFuture<?> future = listeningExecutorService.submit(new Producer(i, oracle, testConfig, testController,
                                                                                ttQueue,
                                                                                inputQueue,
                                                                                enqueuesMap.get(i), invalidMap.get(i)));
      producerFutures.add(future);
    }

    // Start consumer groups
    List<ListenableFuture<?>> consumerGroupFutures = Lists.newArrayList();
    Map<Integer, Queue<Integer>> dequeueMap = Maps.newConcurrentMap();
    for(int i = 0; i < numConsumerGroups; ++i) {
      dequeueMap.put(i, new ConcurrentLinkedQueue<Integer>());
      ConsumerGroup consumerGroup = new ConsumerGroup(i, oracle, listeningExecutorService, testConfig, testController,
                                                      ttQueue, dequeueMap.get(i));
      ListenableFuture<?> future = listeningExecutorService.submit(consumerGroup);
      consumerGroupFutures.add(future);
    }


    // Start the producers and consumers
    LOG.info("Starting test...");
    testController.startTest();

    final Future<?> compositeEnqueueFuture = Futures.allAsList(producerFutures);
    LOG.info("Waiting for enqueues to complete...");
    compositeEnqueueFuture.get();
    LOG.info("Enqueues done.");
    testController.setEnqueueDoneTime(System.currentTimeMillis());

    // Wait for all consumer groups to finish
    final Future<?> compositeConsumerFuture = Futures.allAsList(consumerGroupFutures);
    compositeConsumerFuture.get();

    // Verify if all entries were enqueued properly
    List<Integer> actualEnqueued = Lists.newArrayList(Iterables.concat(enqueuesMap.values()));
    Collections.sort(actualEnqueued);
    List<Integer> actualInvalidated = Lists.newArrayList(Iterables.concat(invalidMap.values()));
    Collections.sort(actualInvalidated);
    List<Integer> actualProcessed = Lists.newArrayList(Iterables.concat(actualEnqueued, actualInvalidated));
    Collections.sort(actualProcessed);
    Assert.assertEquals(actualEnqueued.size(), inputList.size() - actualInvalidated.size());
    Assert.assertEquals(inputList, actualProcessed);

    for(int i = 0; i < numProducers; ++i) {
      LOG.info("Producer:" + i + " enqueueList=" + enqueuesMap.get(i));
      LOG.info("Producer:" + i + " invalidList=" + invalidMap.get(i));
    }

    for(Map.Entry<Integer, Queue<Integer>> group : dequeueMap.entrySet()) {
        List<Integer> dequeuedPerGroup = Lists.newArrayList(group.getValue());
        Collections.sort(dequeuedPerGroup);
        LOG.info(String.format("Group:%d dequeueList=%s", group.getKey(), dequeuedPerGroup));
    }

    LOG.info(String.format("Total entries=%d, Actual enqueued=%d, Invalidated=%d", inputList.size(),
                           actualEnqueued.size(), actualInvalidated.size()));

    // Verify only non-invalidated entries were dequeued
    // Each consumer group should have dequeued all non-invalid entries independently
    for(Map.Entry<Integer, Queue<Integer>> group : dequeueMap.entrySet()) {
      List<Integer> actualDequeuedPerGroup = Lists.newArrayList(group.getValue());
      Collections.sort(actualDequeuedPerGroup);
      LOG.info(String.format("Verifying dequeues of group %d. Expected size=%d, actual size=%d", group.getKey(),
                             actualEnqueued.size(), actualDequeuedPerGroup.size()));
      Assert.assertEquals(actualEnqueued, actualDequeuedPerGroup);
    }
  }

  private TTQueue createQueue(CConfiguration conf) {
    return new TTQueueNewOnVCTable(
      new MemoryOVCTable(Bytes.toBytes("TestMemoryNewTTQueue")),
      Bytes.toBytes(this.getClass().getCanonicalName() + "-" + new Random(System.currentTimeMillis()).nextLong()),
      oracle, conf);
  }
}
