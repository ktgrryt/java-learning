package jq.content;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 既存labを一時コピーして、複数ファイルを編集・検証する問題の定義。
 * sourceDir と除外対象はサーバー側だけが使い、ブラウザへは渡さない。
 */
public record ProjectSpec(
        String name,
        Path sourceDir,
        List<String> excludedPaths,
        List<ProjectFile> files,
        List<String> command,
        int timeoutSeconds,
        String verification) {

    public Map<String, Object> toPublicJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", name);
        json.put("command", String.join(" ", command));
        json.put("verification", verification);
        List<Object> publicFiles = new ArrayList<>();
        for (ProjectFile file : files) {
            publicFiles.add(file.toPublicJson());
        }
        json.put("files", publicFiles);
        json.put("fileCount", files.size());
        json.put("editableFileCount", editableFiles().size());
        return json;
    }

    public List<ProjectFile> editableFiles() {
        return files.stream().filter(ProjectFile::editable).toList();
    }

    public boolean hasSolution() {
        List<ProjectFile> editable = editableFiles();
        return !editable.isEmpty() && editable.stream()
                .allMatch(file -> file.solutionContent() != null);
    }

    public Map<String, Object> solutionFilesJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        for (ProjectFile file : editableFiles()) {
            json.put(file.path(), file.solutionContent());
        }
        return json;
    }
}
