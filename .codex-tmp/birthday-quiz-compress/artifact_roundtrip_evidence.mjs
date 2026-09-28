import { FileBlob, PresentationFile } from "@oai/artifact-tool";

const starterPath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/template-inspection/template-starter.pptx";
const evidencePath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/artifact-roundtrip-evidence.pptx";

const presentation = await PresentationFile.importPptx(await FileBlob.load(starterPath));
const pptx = await PresentationFile.exportPptx(presentation);
await pptx.save(evidencePath);
