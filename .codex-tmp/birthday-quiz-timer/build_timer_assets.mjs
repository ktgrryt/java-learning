import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { FileBlob, PresentationFile } from "@oai/artifact-tool";

const sourcePptx = "/Users/ryoto/Downloads/shohko_birthday_quiz_2026_large_text (1).pptx";
const sourceGif = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/template-inspect/assets/ppt/media/image2.gif";
const outputDir = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/timer-assets";
const manifestPath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/timer-assets-manifest.json";
const questionSlides = [7, 9, 11, 13, 15, 17, 19, 21, 23, 25];

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function gifCommentExtension(label) {
  const payload = Buffer.from(label, "ascii");
  const chunks = [Buffer.from([0x21, 0xfe])];
  for (let offset = 0; offset < payload.length; offset += 255) {
    const chunk = payload.subarray(offset, Math.min(offset + 255, payload.length));
    chunks.push(Buffer.from([chunk.length]), chunk);
  }
  chunks.push(Buffer.from([0x00]));
  return Buffer.concat(chunks);
}

function makeDistinctGif(source, label) {
  if (source.length < 7 || source.subarray(0, 3).toString("ascii") !== "GIF") {
    throw new Error("Source timer is not a GIF file.");
  }
  const trailerIndex = source.lastIndexOf(0x3b);
  if (trailerIndex < 0) throw new Error("GIF trailer was not found.");
  const comment = gifCommentExtension(label);
  return Buffer.concat([
    source.subarray(0, trailerIndex),
    comment,
    source.subarray(trailerIndex),
  ]);
}

async function inspectTimers() {
  const presentation = await PresentationFile.importPptx(await FileBlob.load(sourcePptx));
  if (presentation.slides.items.length !== 28) {
    throw new Error(`Expected 28 slides, found ${presentation.slides.items.length}.`);
  }
  const timers = [];
  for (const slideNumber of questionSlides) {
    const slide = presentation.slides.getItem(slideNumber - 1);
    const layoutBlob = await slide.export({ format: "layout" });
    const layout = JSON.parse(await layoutBlob.text());
    const candidates = layout.elements.filter(
      (element) =>
        element.kind === "image" &&
        element.contentType === "image/gif" &&
        element.bbox?.[0] > 1050 &&
        element.bbox?.[1] < 60,
    );
    if (candidates.length !== 1) {
      throw new Error(`Expected one upper-right GIF on slide ${slideNumber}, found ${candidates.length}.`);
    }
    timers.push({
      slide: slideNumber,
      aid: candidates[0].aid,
      objectId: candidates[0].id,
      bbox: candidates[0].bbox,
      assetId: candidates[0].asset?.assetId,
    });
  }
  return timers;
}

async function main() {
  await fs.mkdir(outputDir, { recursive: true });
  const timers = await inspectTimers();
  const source = await fs.readFile(sourceGif);
  const sourceHash = sha256(source);
  const outputs = [];

  for (const slideNumber of questionSlides) {
    const label = `codex-timer-slide-${String(slideNumber).padStart(2, "0")}`;
    const distinctGif = makeDistinctGif(source, label);
    const outputPath = path.join(outputDir, `timer-slide-${String(slideNumber).padStart(2, "0")}.gif`);
    await fs.writeFile(outputPath, distinctGif);
    outputs.push({
      slide: slideNumber,
      path: outputPath,
      bytes: distinctGif.length,
      sha256: sha256(distinctGif),
      marker: label,
    });
  }

  if (new Set(outputs.map((item) => item.sha256)).size !== outputs.length) {
    throw new Error("Generated timer GIFs are not byte-distinct.");
  }

  await fs.writeFile(
    manifestPath,
    `${JSON.stringify({ sourcePptx, sourceGif, sourceBytes: source.length, sourceHash, timers, outputs }, null, 2)}\n`,
    "utf8",
  );
  console.log(JSON.stringify({ timerCount: outputs.length, sourceHash, manifestPath }, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
