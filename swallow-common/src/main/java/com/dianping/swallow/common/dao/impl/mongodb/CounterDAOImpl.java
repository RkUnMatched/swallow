package com.dianping.swallow.common.dao.impl.mongodb;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.WriteConcern;

public class CounterDAOImpl implements CounterDAO<MessageId> {

   @SuppressWarnings("unused")
   private static final Logger LOG = LoggerFactory.getLogger(CounterDAOImpl.class);

   private final DB            db;

   public CounterDAOImpl(DB db) {
      this.db = db;
   }

   @Override
   public MessageId getMaxMessageId(String topicName, String consumerId) {
      DBCollection collection = this.db.getCollection(topicName);
      DBObject query = BasicDBObjectBuilder.start().add("consumerId", consumerId).get();
      DBObject fields = BasicDBObjectBuilder.start().add("messageId", Integer.valueOf(1)).get();
      DBObject orderBy = BasicDBObjectBuilder.start().add("messageId", Integer.valueOf(-1)).get();
      DBCursor cursor = collection.find(query, fields).sort(orderBy).limit(1);
      DBObject result = cursor.next();
      ObjectId objectId = (ObjectId) result.get("messageId");
      return MessageId.fromObjectId(objectId);

   }

   @Override
   public void add(String topicName, String consumerId, MessageId messageId) {
      DBCollection collection = this.db.getCollection(topicName);
      ObjectId objectId = MessageId.toObjectId(messageId);
      DBObject add = BasicDBObjectBuilder.start().add("consumerId", consumerId).add("messageId", objectId).get();
      collection.insert(add, WriteConcern.SAFE);
   }

   public static void main(String[] args) {
      String uri = "mongodb://localhost:27017";
      MongoClient mongoClient = new MongoClient(uri, new ConfigManager());
      CounterDAOImpl dao = new CounterDAOImpl(mongoClient.mongo.getDB("counter"));

      //test add
      ObjectId objectId = new ObjectId();
      System.out.println(objectId);
      dao.add("topicA", "consumer3", MessageId.fromObjectId(objectId));

      //test getMaxMessageId
      MessageId messageId = dao.getMaxMessageId("topicA", "consumer3");
      System.out.println(messageId);
      System.out.println(MessageId.toObjectId(messageId).toString());

   }

}