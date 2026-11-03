package dev.chatledger.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * chat-ledger application entry point.
 *
 * <p>Scans the {@code dev.chatledger} root package so beans from all modules
 * are discovered automatically when they are on the classpath.
 */
@SpringBootApplication(scanBasePackages = "dev.chatledger")
public class ChatLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatLedgerApplication.class, args);
    }
}
