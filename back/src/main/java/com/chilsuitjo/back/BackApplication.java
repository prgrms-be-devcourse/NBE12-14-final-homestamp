package com.chilsuitjo.back;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackApplication {

  public static void main(String[] args) {
    if (MigrationMode.isRequested(args)) {
      int exitCode = MigrationMode.execute(args);
      if (exitCode != 0) {
        System.exit(exitCode);
      }
      return;
    }
    SpringApplication.run(BackApplication.class, args);
  }
}
