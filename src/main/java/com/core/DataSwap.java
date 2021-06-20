package com.core;

public interface DataSwap {
    /**
     * set the bing connection
     * @param dataSwap
     */
    public void setTarget(DataSwap dataSwap);

    /**
     * sendData to the bind connection
     * @param bytes
     */
    public void sendData(byte[] bytes);

    /**
     * receiveData from the bind connection
     * @param bytes
     */
    public void receiveData(byte[] bytes);


    public boolean hasClosed();

    public void closeSwap();

}
