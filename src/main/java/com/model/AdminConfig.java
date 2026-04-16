package com.model;

import lombok.Data;

@Data
public class AdminConfig {

    private String host = "127.0.0.1";

    private int port = 8080;

    private String password = "admin";

    private int sessionTimeoutMinutes = 720;
}
