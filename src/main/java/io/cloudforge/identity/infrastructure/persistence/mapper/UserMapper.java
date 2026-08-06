package io.cloudforge.identity.infrastructure.persistence.mapper;

import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.infrastructure.persistence.entity.UserEntity;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserEntity toEntity(User user) {

        return new UserEntity(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getPasswordHash(),
                user.getStatus()
        );
    }

    public static User toDomain(UserEntity entity) {

        return User.restore(
                entity.getId(),
                entity.getEmail(),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getPasswordHash(),
                entity.getStatus()
        );
    }

}