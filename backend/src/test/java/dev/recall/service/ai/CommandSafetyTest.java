package dev.recall.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandSafetyTest {

    private final CommandSafety safety = new CommandSafety();

    @ParameterizedTest
    @ValueSource(strings = {
            "rm -rf /",
            "sudo rm -rf /",
            "rm -fr /*",
            "rm -rf ~",
            "rm -rf ~/",
            "rm -rf $HOME",
            "rm -r -f /",
            "rm --recursive --force /",
            "RM -RF /",
            "rm -rf / --no-preserve-root",
            "cd /tmp && rm -rf / ; echo done",
            "mkfs.ext4 /dev/sda1",
            "sudo mkfs -t xfs /dev/nvme0n1",
            "dd if=/dev/zero of=/dev/sda bs=1M",
            ":(){ :|:& };:",
            "shutdown -h now",
            "sudo reboot",
            "poweroff",
            "Stop-Computer -Force",
            "echo hi > /dev/sda",
            "format C:",
            "chmod -R 777 /",
            "git push --force",
            "git push -f origin main",
            "git push origin main --force",
            "git push --force origin master",
            "git push origin +main",
            "git push --force-with-lease origin main",
            "git push -f origin HEAD:refs/heads/production",
            "git push --force origin release/2.1"})
    void destructiveCommandsAreDenied(String command) {
        assertThat(safety.isDestructive(command)).as(command).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rm -rf node_modules",
            "rm -rf ./dist",
            "rm -rf /tmp/build-cache",
            "rm -rf ~/.npm/_cacache",
            "rm -f /tmp/app.pid",
            "npx kill-port 3000",
            "git push",
            "git push origin main",
            "git push --force-with-lease",
            "git push --force-with-lease origin feature/login",
            "git push -f origin feature/login",
            "git push origin +feature/x",
            "docker system prune -af",
            "dd --help",
            "echo shutdown later",
            "npm run format",
            "chmod +x scripts/deploy.sh",
            ""})
    void everydayCommandsAreAllowed(String command) {
        assertThat(safety.isDestructive(command)).as(command).isFalse();
    }
}
