package com.util;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockValueFeature<T> {
    private static final Logger log = LoggerFactory.getLogger(BlockValueFeature.class);

    private T data;

    public BlockValueFeature() {
    }

    public void complete(T t) {
        log.debug("complete by value: " + t);
        data = t;
        synchronized (this) {
            this.notify();
        }
    }

    public T get(Integer second) {
        synchronized (this) {
            try {
                if (second > 0) {
                    this.wait(second * 1000);
                } else {
                    this.wait();
                }
                log.debug("return value: " + data);
                T t2 = data;
                data = null;
                return t2;
            } catch (InterruptedException e) {
                throw new RuntimeException("get value fail cause " + e);
            }
        }
    }

    public T get() {
        return get(-1);
    }


}
