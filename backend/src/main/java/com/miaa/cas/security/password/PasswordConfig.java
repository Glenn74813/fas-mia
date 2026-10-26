package com.miaa.cas.security.password;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /**
     * Argon2id: 16-byte salt, 32-byte hash, parallelism 1, 16 MiB memory, 3 iterations. Re-tune memory
     * and iterations on production hardware to roughly 250 ms per hash.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 1 << 14, 3);
    }
}
