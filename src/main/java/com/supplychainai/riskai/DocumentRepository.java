package com.supplychainai.riskai;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DocumentRepository extends MongoRepository<DocumentMetadata,ObjectId> {

}
