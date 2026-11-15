package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.constants.MediaConstants;
import ctu.soulspace.dto.request.SignUpRequest;
import ctu.soulspace.dto.response.LoginUserResponse;
import ctu.soulspace.dto.response.UserResponse;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse signUp(SignUpRequest request) {
        String username = normalizeUsername(request.getUsername());
        String fullname = normalizeFullname(request.getFullname());
        LocalDate dob = resolveDob(request.getDob(), request.getBirthday());
        validatePassword(request.getPassword());

        if (userRepository.existsByUsername(username)) {
            throw new AppException(ErrorCode.USERNAME_EXISTS);
        }

        // Hashing happens before the repository opens its write transaction.
        String hash = passwordEncoder.encode(request.getPassword());
        User user = new User();
        user.setUsername(username);
        user.setFullname(fullname);
        user.setDob(dob);
        user.setPasswordHash(hash);

        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "users_username_key".equals(violation.getConstraintName())) {
                    throw new AppException(ErrorCode.USERNAME_EXISTS);
                }
            }
            throw exception;
        }

        log.info("event=user_registered user_id={}", saved.getId());
        return new UserResponse(
                saved.getId(),
                saved.getUsername(),
                saved.getFullname(),
                saved.getDob(),
                avatarUrl(saved));
    }

    public LoginUserResponse findLoginUser(UUID userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new AppException(ErrorCode.AUTH_REQUIRED));
        return new LoginUserResponse(user.getId(), user.getUsername(), avatarUrl(user));
    }

    public UserResponse profile(UUID id, UUID viewer) {
        User user =
                userRepository
                        .findById(id)
                        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getFullname(),
                id.equals(viewer) ? user.getDob() : null,
                avatarUrl(user));
    }

    private String normalizeUsername(String value) {
        if (value == null) {
            throw invalid("username", "Username is required");
        }
        String username = value.trim().toLowerCase(Locale.ROOT);
        if (!username.matches("^[a-z0-9]{3,32}$")) {
            throw invalid("username", "Username must contain 3 to 32 letters or digits");
        }
        return username;
    }

    private String normalizeFullname(String value) {
        if (value == null) {
            throw invalid("fullname", "Fullname is required");
        }
        String fullname = value.trim();
        int length = fullname.codePointCount(0, fullname.length());
        if (length < 1 || length > 100) {
            throw invalid("fullname", "Fullname must contain 1 to 100 characters");
        }
        return fullname;
    }

    private LocalDate resolveDob(LocalDate dob, LocalDate birthday) {
        if (dob != null && birthday != null && !dob.equals(birthday)) {
            throw invalid("dob", "dob and birthday must match");
        }
        LocalDate result = dob != null ? dob : birthday;
        if (result != null && !result.isBefore(LocalDate.now(APP_ZONE))) {
            throw invalid("dob", "Date of birth must be in the past");
        }
        return result;
    }

    private void validatePassword(String password) {
        if (password == null) {
            throw invalid("password", "Password is required");
        }
        int length = password.codePointCount(0, password.length());
        if (length < 4
                || length > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 512
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(
                    "password",
                    "Use 4 to 128 characters, at most 512 UTF-8 bytes, without control characters");
        }
    }

    private String avatarUrl(User user) {
        return user.getAvatarMediaId() == null
                ? MediaConstants.DEFAULT_AVATAR_URL
                : "/api/v1/media/" + user.getAvatarMediaId();
    }

    private AppException invalid(String field, String message) {
        return new AppException(ErrorCode.VALIDATION_FAILED, Map.of(field, message));
    }
}
