package com.sportday.service;

import com.sportday.dto.RegisterRequest;
import com.sportday.dto.UserDTO;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.sportday.repository.StudentRepository studentRepository;

    public UserDTO getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return withRoster(user);
    }

    public List<UserDTO> getAllUsers() {
        return userRepository.findAll().stream()
                .map(UserDTO::from)
                .collect(Collectors.toList());
    }

    public UserDTO updateUser(Long id, UserDTO userDTO) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        if (userDTO.getEmail() != null && !userDTO.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(userDTO.getEmail())) {
                throw new IllegalArgumentException("Email already exists");
            }
            user.setEmail(userDTO.getEmail());
        }

        if (userDTO.getFullName() != null) user.setFullName(userDTO.getFullName());
        if (userDTO.getAge() != null) user.setAge(userDTO.getAge());
        if (userDTO.getGender() != null) user.setGender(userDTO.getGender());

        User saved = userRepository.save(user);
        return UserDTO.from(saved);
    }

    public void setUserEnabled(Long id, boolean enabled) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        user.setEnabled(enabled);
        userRepository.save(user);
    }

    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
    }

    @Transactional
    public UserDTO createManager(RegisterRequest request) {
        return createUser(request, User.Role.MANAGER);
    }

    /**
     * Creates a staff account. This is the only way an account is created now that
     * public self-registration has been removed — students arrive through the
     * register import instead.
     */
    @Transactional
    public UserDTO createUser(RegisterRequest request, User.Role role) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("A username is required.");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("A password is required.");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists");
        }
        String email = request.getEmail() == null || request.getEmail().isBlank()
                ? null : request.getEmail().trim();
        if (email != null && userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        User user = User.builder()
                .username(request.getUsername().trim())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(email)
                .fullName(request.getFullName())
                .age(request.getAge())
                .gender(request.getGender())
                .role(role == null ? User.Role.MANAGER : role)
                .enabled(true)
                .build();

        return UserDTO.from(userRepository.save(user));
    }

    /** The roles an administrator is allowed to hand out. */
    public List<String> assignableRoles() {
        return List.of(User.Role.ADMIN.name(), User.Role.MANAGER.name(),
                User.Role.TEACHER.name(), User.Role.USER.name());
    }

    public UserDTO getCurrentUserProfile(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return withRoster(user);
    }

    /**
     * The account, plus its roster record when it has one.
     *
     * <p>A student's grade lives on the register, not on the account, and the entry
     * pages need it to leave out the events that grade may not enter — so it is
     * looked up here rather than left for a client to infer from what the student
     * happens to have entered already.</p>
     */
    private UserDTO withRoster(User user) {
        UserDTO dto = UserDTO.from(user);
        studentRepository.findByUserId(user.getId())
                .ifPresent(student -> UserDTO.applyRoster(dto, student));
        return dto;
    }
}
