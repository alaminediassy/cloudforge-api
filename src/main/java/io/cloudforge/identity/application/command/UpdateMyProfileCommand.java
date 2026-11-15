package io.cloudforge.identity.application.command;

/**
 * Contains the requested name changes.
 *
 * <p>A null field means no change.
 * The API must reject explicit JSON null values before creating this command.
 */
public record UpdateMyProfileCommand(
        String firstName,
        String lastName
) {
}