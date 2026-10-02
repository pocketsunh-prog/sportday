package com.sportday.service;

import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves the signed-in account (and, for students, the roster record behind
 * it) so controllers do not each repeat the lookup.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;

    @Transactional(readOnly = true)
    public User require(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResourceNotFoundException("No authenticated user");
        }
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + authentication.getName()));
    }

    @Transactional(readOnly = true)
    public Long requireId(Authentication authentication) {
        return require(authentication).getId();
    }

    /** The student record for the signed-in account; empty for staff accounts. */
    @Transactional(readOnly = true)
    public Optional<Student> roster(Authentication authentication) {
        return studentRepository.findWithUserByUserId(requireId(authentication));
    }
}
