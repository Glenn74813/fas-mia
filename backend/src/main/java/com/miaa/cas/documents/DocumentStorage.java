package com.miaa.cas.documents;

/** Content store for supporting documents. Implementations must be shared by all application instances. */
public interface DocumentStorage {

    void put(String key, byte[] content);

    byte[] get(String key);
}
