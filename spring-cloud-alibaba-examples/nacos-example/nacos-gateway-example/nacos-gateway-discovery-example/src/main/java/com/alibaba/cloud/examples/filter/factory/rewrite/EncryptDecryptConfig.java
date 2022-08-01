package com.alibaba.cloud.examples.filter.factory.rewrite;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class EncryptDecryptConfig {

  private String name;

  private URI encDecUri;

  private Long timeout = 5*1000L;

  private List<String> excludeUris = new ArrayList<>();

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public URI getEncDecUri() {
    return encDecUri;
  }

  public void setEncDecUri(URI encDecUri) {
    this.encDecUri = encDecUri;
  }

  public List<String> getExcludeUris() {
    return excludeUris;
  }

  public void setExcludeUris(List<String> excludeUris) {
    this.excludeUris = excludeUris;
  }

  public Long getTimeout() {
    return timeout;
  }

  public void setTimeout(Long timeout) {
    this.timeout = timeout;
  }
}
