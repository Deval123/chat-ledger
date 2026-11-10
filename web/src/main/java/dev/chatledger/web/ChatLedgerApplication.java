package dev.chatledger.web;

import dev.chatledger.provisioning.ProvisioningCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * chat-ledger application entry point.
 *
 * <p>Scans the {@code dev.chatledger} root package so beans from all modules
 * are discovered automatically when they are on the classpath.
 *
 * <p>The operator commands {@code provision-tenant} and {@code provision-user} run here
 * without starting the web application: no server, no sessions, only a database login
 * that can execute the provisioning functions.
 */
@SpringBootApplication(scanBasePackages = "dev.chatledger")
public class ChatLedgerApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].startsWith("provision-")) {
            System.exit(ProvisioningCommand.main(args));
            return;
        }
        if (args.length > 0 && args[0].startsWith("demo-")) {
            // The demo operator's commands (task 9): no web server, the demo operator login and the application login only.
            System.exit(dev.chatledger.demo.ops.DemoCommand.main(args));
            return;
        }
        SpringApplication.run(ChatLedgerApplication.class, args);
    }
}
