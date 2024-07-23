package com.model;

import lombok.Data;

@Data
public class Mapping {
    private int listenPort;

    private int forwardPort;

    private String name;

    private String forwardHost;
}
