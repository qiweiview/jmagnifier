package com.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class DumpConfig {

    private Boolean enable = false;

    private String dumpPath = "/tmp/j_magnifier";
}
