package com.dianping.swallow.consumerserver.worker;

import java.util.Map;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.util.internal.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dianping.swallow.common.consumer.ConsumerType;
import com.dianping.swallow.common.consumer.MessageFilter;
import com.dianping.swallow.common.internal.consumer.ACKHandlerType;
import com.dianping.swallow.common.internal.dao.AckDAO;
import com.dianping.swallow.common.internal.dao.MessageDAO;
import com.dianping.swallow.common.internal.threadfactory.MQThreadFactory;
import com.dianping.swallow.common.internal.util.ProxyUtil;
import com.dianping.swallow.consumerserver.Heartbeater;
import com.dianping.swallow.consumerserver.buffer.SwallowBuffer;
import com.dianping.swallow.consumerserver.config.ConfigManager;

public class ConsumerWorkerManager {

   private static final Logger             LOG                       = LoggerFactory
                                                                           .getLogger(ConsumerWorkerManager.class);

   private AckDAO                          ackDAO;
   private Heartbeater                     heartbeater;
   private SwallowBuffer                   swallowBuffer;
   private MessageDAO                      messageDAO;
   
   private ConfigManager                   configManager             = ConfigManager.getInstance();

   private MQThreadFactory                 threadFactory             = new MQThreadFactory();

   private Map<ConsumerId, ConsumerWorker> consumerId2ConsumerWorker = new ConcurrentHashMap<ConsumerId, ConsumerWorker>();

   private Thread idleWorkerManagerCheckerThread;
   private Thread maxAckedMessageIdUpdaterThread;
   private Map<ConsumerId, Long> consumerId2MaxSavedAckedMessageId = new ConcurrentHashMap<ConsumerId, Long>();
   private volatile boolean closed = false;
   
   public void setAckDAO(AckDAO ackDAO) {
      this.ackDAO = ProxyUtil.createMongoDaoProxyWithRetryMechanism(ackDAO, configManager.getRetryIntervalWhenMongoException());
      //this.ackDAO = ackDAO;
   }

   public MQThreadFactory getThreadFactory() {
      return threadFactory;
   }

   public void setHeartbeater(Heartbeater heartbeater) {
      this.heartbeater = heartbeater;
   }

   public void setSwallowBuffer(SwallowBuffer swallowBuffer) {
      this.swallowBuffer = swallowBuffer;
   }

   public void setMessageDAO(MessageDAO messageDAO) {
      this.messageDAO = ProxyUtil.createMongoDaoProxyWithRetryMechanism(messageDAO,configManager.getRetryIntervalWhenMongoException());
      //this.messageDAO = messageDAO;
   }

   public ConfigManager getConfigManager() {
      return configManager;
   }

   public void handleGreet(Channel channel, ConsumerInfo consumerInfo, int clientThreadCount, MessageFilter messageFilter) {
      findOrCreateConsumerWorker(consumerInfo, messageFilter).handleGreet(channel, clientThreadCount);
   }

   public void handleAck(Channel channel, ConsumerInfo consumerInfo, Long ackedMsgId, ACKHandlerType type) {
      ConsumerWorker worker = findConsumerWorker(consumerInfo);
      if (worker != null) {
         worker.handleAck(channel, ackedMsgId, type);
      } else {
         LOG.warn(consumerInfo + "ConsumerWorker is not exist!");
         channel.close();
      }
   }

   public void handleChannelDisconnect(Channel channel, ConsumerInfo consumerInfo) {
      ConsumerWorker worker = findConsumerWorker(consumerInfo);
      if (worker != null) {
         worker.handleChannelDisconnect(channel);
      }
   }

   public void close() {
      for (Map.Entry<ConsumerId, ConsumerWorker> entry : consumerId2ConsumerWorker.entrySet()) {
         entry.getValue().closeMessageFetcherThread();
      }
      try {
         Thread.sleep(configManager.getWaitAckTimeWhenCloseSwc());
      } catch (InterruptedException e) {
         LOG.error("close Swc thread InterruptedException", e);
      }
      for (Map.Entry<ConsumerId, ConsumerWorker> entry : consumerId2ConsumerWorker.entrySet()) {
         entry.getValue().closeAckExecutor();
      }
      for (Map.Entry<ConsumerId, ConsumerWorker> entry : consumerId2ConsumerWorker.entrySet()) {
         entry.getValue().close();
      }
      closed = true;
      if (idleWorkerManagerCheckerThread != null) {
         try {
            idleWorkerManagerCheckerThread.join();
            consumerId2ConsumerWorker = new ConcurrentHashMap<ConsumerId, ConsumerWorker>();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
      if (maxAckedMessageIdUpdaterThread != null) {
         try {
            maxAckedMessageIdUpdaterThread.join();
            consumerId2MaxSavedAckedMessageId = new ConcurrentHashMap<ConsumerId, Long>();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
   }

   private ConsumerWorker findConsumerWorker(ConsumerInfo consumerInfo) {
      ConsumerId consumerId = consumerInfo.getConsumerId();
      return consumerId2ConsumerWorker.get(consumerId);
   }

   public Map<ConsumerId, ConsumerWorker> getConsumerId2ConsumerWorker() {
      return consumerId2ConsumerWorker;
   }

   public ConsumerWorker findOrCreateConsumerWorker(ConsumerInfo consumerInfo,MessageFilter messageFilter) {
      ConsumerWorker worker = findConsumerWorker(consumerInfo);
      if (worker == null) {
         synchronized (consumerId2ConsumerWorker) {
            if ( (worker = findConsumerWorker(consumerInfo)) == null) {
               worker = new ConsumerWorkerImpl(consumerInfo, this, messageFilter);
               ConsumerId consumerId = consumerInfo.getConsumerId();
               consumerId2ConsumerWorker.put(consumerId, worker);
            }
         }
      }
      return worker;
   }

   public void init(boolean isSlave) {
	   
      startIdleWorkerCheckerThread();
      startMaxAckedMessageIdUpdaterThread();
	   
      if (!isSlave) {
         startHeartbeater(configManager.getMasterIp());
      }

   }
   
   private void startMaxAckedMessageIdUpdaterThread() {
      maxAckedMessageIdUpdaterThread = threadFactory.newThread(new Runnable() {
         
         @Override
         public void run() {
            while (!closed) {
               for (Map.Entry<ConsumerId, ConsumerWorker> entry : consumerId2ConsumerWorker.entrySet()) {
                  ConsumerWorker worker = entry.getValue();
                  ConsumerId consumerId = entry.getKey();
                  updateMaxAckedMessageId(worker, consumerId);
               }
               try {
                  Thread.sleep(configManager.getMaxAckedMessageIdUpdateInterval());
               } catch (InterruptedException e) {
                  break;
               }
            }
            LOG.info("MaxAckedMessageIdUpdaterThread closed");
         }

      }, "maxAckedMessageIdUpdaterThread-");
      maxAckedMessageIdUpdaterThread.start();
   }
   
   private void updateMaxAckedMessageId(ConsumerWorker worker, ConsumerId consumerId) {
      if(worker.getConsumerType() == ConsumerType.DURABLE_AT_LEAST_ONCE) {
         Long lastSavedAckedMsgId = consumerId2MaxSavedAckedMessageId.get(consumerId);
         lastSavedAckedMsgId = lastSavedAckedMsgId == null ? 0 : lastSavedAckedMsgId;
         Long currentMaxAckedMsgId = worker.getMaxAckedMessageId();
         if(currentMaxAckedMsgId > 0 && currentMaxAckedMsgId > lastSavedAckedMsgId) {
            ackDAO.add(consumerId.getDest().getName(), consumerId.getConsumerId(), currentMaxAckedMsgId, "batch");
            consumerId2MaxSavedAckedMessageId.put(consumerId, currentMaxAckedMsgId);
         }
      }
   }

   private void startIdleWorkerCheckerThread() {
      idleWorkerManagerCheckerThread = threadFactory.newThread(new Runnable() {

         @Override
         public void run() {
            while (!closed) {
               for (Map.Entry<ConsumerId, ConsumerWorker> entry : consumerId2ConsumerWorker.entrySet()) {
                  ConsumerWorker worker = entry.getValue();
                  ConsumerId consumerId = entry.getKey();
                  if(worker.allChannelDisconnected()) {
                     updateMaxAckedMessageId(worker, consumerId);
                     workerDone(consumerId);
                     worker.closeMessageFetcherThread();
                     worker.closeAckExecutor();
                     worker.close();
                     LOG.info("ConsumerWorker for " + consumerId + " has no connected channel, close it");
                  }
               }
               try {
                  Thread.sleep(configManager.getCheckConnectedChannelInterval());
               } catch (InterruptedException e) {
                  break;
               }
            }
            LOG.info("idle ConsumerWorker checker thread closed");
         }

      }, "idleConsumerWorkerChecker-");
      idleWorkerManagerCheckerThread.start();
   }

   private void startHeartbeater(final String ip) {

      Runnable runnable = new Runnable() {

         @Override
         public void run() {
            while (true) {

               try {
                  heartbeater.beat(ip);
                  Thread.sleep(configManager.getHeartbeatUpdateInterval());
               } catch (Exception e) {
                  LOG.error("Error update heart beat", e);
               }
            }
         }

      };

      Thread heartbeatThread = threadFactory.newThread(runnable, "heartbeat-");
      heartbeatThread.setDaemon(true);
      heartbeatThread.start();
   }

   public void workerDone(ConsumerId consumerId) {
      consumerId2MaxSavedAckedMessageId.remove(consumerId);
      consumerId2ConsumerWorker.remove(consumerId);
   }

   public AckDAO getAckDAO() {
      return ackDAO;
   }

   public SwallowBuffer getSwallowBuffer() {
      return swallowBuffer;
   }

   public MessageDAO getMessageDAO() {
      return messageDAO;
   }


}
