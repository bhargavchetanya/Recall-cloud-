package dev.recall.service.errors;

import java.util.List;

/**
 * Real error output from npm, git, docker, maven, python and kubectl. Each SAME pair is one problem that happened
 * with different ports, paths, ids or times and must get one fingerprint; each DIFFERENT pair is two problems that
 * look alike and must not. Shared by the unit test and T7.
 */
public final class ErrorSamples {

    private ErrorSamples() {}

    public record Pair(String name, String a, String b) {}

    public static final List<Pair> SAME = List.of(
            new Pair("node port in use (3000 vs 8080)",
                    """
                    node:events:497
                          throw er; // Unhandled 'error' event
                          ^
                    Error: listen EADDRINUSE: address already in use :::3000
                        at Server.setupListenHandle [as _listen2] (node:net:1872:16)
                        at listenInCluster (node:net:1920:12)
                    { code: 'EADDRINUSE', errno: -98, syscall: 'listen', address: '::', port: 3000 }
                    """,
                    """
                    node:events:497
                          throw er; // Unhandled 'error' event
                          ^
                    Error: listen EADDRINUSE: address already in use :::8080
                        at Server.setupListenHandle [as _listen2] (node:net:1872:16)
                        at listenInCluster (node:net:1920:12)
                    { code: 'EADDRINUSE', errno: -98, syscall: 'listen', address: '::', port: 8080 }
                    """),
            new Pair("git push rejected (different remotes)",
                    """
                    To https://github.com/acme/shop.git
                     ! [rejected]        main -> main (fetch first)
                    error: failed to push some refs to 'https://github.com/acme/shop.git'
                    hint: Updates were rejected because the remote contains work that you do not have locally.
                    """,
                    """
                    To github.com:acme/api.git
                     ! [rejected]        main -> main (fetch first)
                    error: failed to push some refs to 'github.com:acme/api.git'
                    hint: Updates were rejected because the remote contains work that you do not have locally.
                    """),
            new Pair("docker container name conflict (different names and ids)",
                    "docker: Error response from daemon: Conflict. The container name \"/shop-db\" is already in use "
                            + "by container \"4f3c2a1b9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b\". "
                            + "You have to remove (or rename) that container to be able to reuse that name.\n"
                            + "See 'docker run --help'.",
                    "docker: Error response from daemon: Conflict. The container name \"/api-cache\" is already in "
                            + "use by container \"9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b\". "
                            + "You have to remove (or rename) that container to be able to reuse that name.\n"
                            + "See 'docker run --help'."),
            new Pair("maven compile failure (different paths and positions)",
                    """
                    [INFO] BUILD FAILURE
                    [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project shop: Compilation failure
                    [ERROR] /home/ada/shop/src/main/java/dev/shop/App.java:[12,8] cannot find symbol
                    [ERROR]   symbol:   class Clock
                    """,
                    """
                    [INFO] BUILD FAILURE
                    [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project shop: Compilation failure
                    [ERROR] C:\\Users\\bo\\work\\shop\\src\\main\\java\\dev\\shop\\App.java:[40,15] cannot find symbol
                    [ERROR]   symbol:   class Clock
                    """),
            new Pair("python missing module (different paths and lines)",
                    """
                    Traceback (most recent call last):
                      File "/home/ada/app/main.py", line 3, in <module>
                        import requests
                    ModuleNotFoundError: No module named 'requests'
                    """,
                    """
                    Traceback (most recent call last):
                      File "C:\\Users\\bo\\proj\\run.py", line 11, in <module>
                        import requests
                    ModuleNotFoundError: No module named 'requests'
                    """),
            new Pair("kubectl pod not found (different pod names)",
                    "Error from server (NotFound): pods \"api-7d9f8b6c5-x2x9k\" not found",
                    "Error from server (NotFound): pods \"worker-5c6d7e8f9-q8w7e\" not found"),
            new Pair("connection refused with timestamps and ports",
                    "2026-09-24T10:15:02.123Z ERROR Connection refused: db:5432 (attempt 3 of 5)",
                    "2026-09-25 18:01:44 ERROR Connection refused: db:6543 (attempt 1 of 5)"));

    public static final List<Pair> DIFFERENT = List.of(
            new Pair("port in use vs permission denied on a port",
                    "Error: listen EADDRINUSE: address already in use :::3000",
                    "Error: listen EACCES: permission denied 0.0.0.0:80"),
            new Pair("not a git repository vs unrelated histories",
                    "fatal: not a git repository (or any of the parent directories): .git",
                    "fatal: refusing to merge unrelated histories"),
            new Pair("npm missing package.json vs dependency conflict",
                    """
                    npm ERR! code ENOENT
                    npm ERR! syscall open
                    npm ERR! path /home/ada/app/package.json
                    npm ERR! enoent ENOENT: no such file or directory, open '/home/ada/app/package.json'
                    """,
                    """
                    npm ERR! code ERESOLVE
                    npm ERR! ERESOLVE unable to resolve dependency tree
                    npm ERR! Found: react@18.3.1
                    npm ERR! Could not resolve dependency: peer react@"^17.0.0" from some-lib@2.1.0
                    """));

    /** Every sample, for checks that should hold on all of them. */
    public static List<String> all() {
        return java.util.stream.Stream.concat(SAME.stream(), DIFFERENT.stream())
                .flatMap(p -> java.util.stream.Stream.of(p.a(), p.b()))
                .toList();
    }
}
