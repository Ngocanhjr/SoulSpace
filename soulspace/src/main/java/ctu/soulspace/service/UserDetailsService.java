package ctu.soulspace.service;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.entities.User;
import ctu.soulspace.repositories.UserRepository;

@Service
public class UserDetailsService implements org.springframework.security.core.userdetails.UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String value) {
        if (value == null) {
            throw invalidCredentials();
        }
        String username = value.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByUsername(username)
            .orElseThrow(this::invalidCredentials);
        // DaoAuthenticationProvider performs the same dummy-hash path as an absent user.
        if (user.getPasswordHash() == null) {
            throw invalidCredentials();
        }
        return org.springframework.security.core.userdetails.User
            .withUsername(user.getId().toString())
            .password(user.getPasswordHash())
            .authorities("ROLE_USER")
            .build();
    }

    private UsernameNotFoundException invalidCredentials() {
        return new UsernameNotFoundException(ErrorCode.INVALID_CREDENTIALS.getMessage());
    }
}
