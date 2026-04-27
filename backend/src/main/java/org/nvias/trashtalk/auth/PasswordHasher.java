package org.nvias.trashtalk.auth;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

    private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);

    private final int memoryKib;
    private final int iterations;
    private final int parallelism;

    public PasswordHasher(
            @Value("${trashtalk.argon2.memory-kib:65536}") int memoryKib,
            @Value("${trashtalk.argon2.iterations:3}") int iterations,
            @Value("${trashtalk.argon2.parallelism:1}") int parallelism) {
        this.memoryKib = memoryKib;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    public String hash(char[] password) {
        try {
            return argon2.hash(iterations, memoryKib, parallelism, password);
        } finally {
            argon2.wipeArray(password);
        }
    }

    public boolean verify(String hash, char[] password) {
        try {
            return argon2.verify(hash, password);
        } finally {
            argon2.wipeArray(password);
        }
    }
}
