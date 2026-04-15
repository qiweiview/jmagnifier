package com.model;

import lombok.Data;

@Data
public class StoreConfig {

    private String sqlitePath = "./data/jmagnifier.db";

    private String spillDir = "./data/spill";
}
