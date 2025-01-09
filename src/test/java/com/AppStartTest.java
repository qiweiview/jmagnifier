package com;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

@Slf4j
public class AppStartTest {


    @Test
    public void commonMode() {
        String[] strings = {};
        AppStart.main(strings);
    }

    @Test
    public void paramMode() {
        String[] strings = {"8001", "j-magnifier.com", "80"};
        AppStart.main(strings);
    }

    @Test
    public void fileMode() {
        String[] strings = {"D:\\JAVA_WORK_SPACE\\jmagnifier\\src\\main\\resources\\config.yml"};
        AppStart.main(strings);
        Thread thread = Thread.currentThread();
        synchronized (thread) {
            try {
                thread.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }
}
