package jq.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** コマンドを専用のPOSIXプロセスグループで実行し、終了時にグループ全体を回収する。 */
final class ProcessGroupCommand {

    private static final String WRAPPER = """
            set -m
            child=
            cleanup() {
              if [ -n "$child" ]; then
                kill -TERM -- "-$child" 2>/dev/null || true
                sleep 0.05
                kill -KILL -- "-$child" 2>/dev/null || true
              fi
            }
            trap cleanup EXIT
            trap 'exit 143' INT TERM
            "$@" &
            child=$!
            wait "$child"
            status=$?
            exit "$status"
            """;

    private ProcessGroupCommand() {
    }

    /** 引数をshell文字列へ展開せず、そのまま位置引数として安全に渡す。 */
    static List<String> wrap(List<String> command) {
        List<String> wrapped = new ArrayList<>(command.size() + 4);
        wrapped.add(Files.isExecutable(Path.of("/bin/bash")) ? "/bin/bash" : "bash");
        wrapped.add("-c");
        wrapped.add(WRAPPER);
        wrapped.add("jq-process-group-wrapper");
        wrapped.addAll(command);
        return wrapped;
    }
}
