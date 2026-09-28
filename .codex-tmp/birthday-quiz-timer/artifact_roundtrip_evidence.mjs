import { FileBlob, PresentationFile } from "@oai/artifact-tool";

const starterPptx = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/template-starter.pptx";
const evidencePptx = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/artifact-roundtrip-evidence.pptx";

async function main() {
  const presentation = await PresentationFile.importPptx(await FileBlob.load(starterPptx));
  const exported = await PresentationFile.exportPptx(presentation);
  await exported.save(evidencePptx);
  console.log(JSON.stringify({ slides: presentation.slides.items.length, evidencePptx }));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
