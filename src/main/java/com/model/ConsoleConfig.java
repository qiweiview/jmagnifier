package com.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class ConsoleConfig {

    private Boolean printRequest = true;

    private Boolean printResponse = true;
}
