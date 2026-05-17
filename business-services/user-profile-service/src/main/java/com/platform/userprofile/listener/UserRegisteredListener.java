package com.platform.userprofile.listener;
import com.platform.common.events.UserRegisteredEvent;
import com.platform.userprofile.entity.Profile;
import com.platform.userprofile.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j @Component @RequiredArgsConstructor
public class UserRegisteredListener {
    private final ProfileRepository profileRepository;

    @RabbitListener(queues = "user.profile.queue")
    public void onUserRegistered(UserRegisteredEvent event) {
        if (profileRepository.existsById(event.getUserId())) return;
        profileRepository.save(Profile.builder()
                .id(event.getUserId()).fullName(event.getFullName()).build());
        log.info("Created profile for user: {}", event.getUserId());
    }
}
