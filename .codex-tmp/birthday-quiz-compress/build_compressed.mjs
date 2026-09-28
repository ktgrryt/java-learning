import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import JSZip from "jszip";
import sharp from "sharp";
import { FileBlob, PresentationFile } from "@oai/artifact-tool";

const sourcePath = "/Users/ryoto/code/java-learning/shohko_birthday_quiz_2026_timer_reset.pptx";
const finalPath = "/Users/ryoto/code/java-learning/shohko_birthday_quiz_2026_timer_reset_compressed.pptx";
const inventoryPath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/media-inventory.json";
const auditPath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/final-package-audit.json";
const artifactInspectPath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/artifact-tool-inspect.ndjson";
const densityMultiplier = 2;

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function targetDimensions(item) {
  if (item.format === "gif") return { width: 256, height: 256, scale: 256 / item.width };
  let scale = 0;
  for (const usage of item.usages) {
    if (!usage.displayPx96) continue;
    const visible = usage.visibleFraction || [1, 1];
    const neededWidth = usage.displayPx96[0] * densityMultiplier / Math.max(visible[0], 0.01);
    const neededHeight = usage.displayPx96[1] * densityMultiplier / Math.max(visible[1], 0.01);
    scale = Math.max(scale, neededWidth / item.width, neededHeight / item.height);
  }
  scale = Math.min(1, scale || 1);
  return {
    width: Math.max(1, Math.round(item.width * scale)),
    height: Math.max(1, Math.round(item.height * scale)),
    scale,
  };
}

function injectGifComment(bytes, commentText) {
  const trailer = bytes.lastIndexOf(0x3b);
  if (trailer < 0) throw new Error("GIF trailer not found");
  const comment = Buffer.from(commentText, "ascii");
  if (comment.length > 255) throw new Error("GIF comment is too long");
  return Buffer.concat([
    bytes.subarray(0, trailer),
    Buffer.from([0x21, 0xfe, comment.length]),
    comment,
    Buffer.from([0x00]),
    bytes.subarray(trailer),
  ]);
}

async function optimizeMedia(item, inputBytes) {
  const target = targetDimensions(item);
  let outputBytes;
  if (item.format === "gif") {
    const metadata = await sharp(inputBytes, { animated: true, limitInputPixels: false }).metadata();
    outputBytes = await sharp(inputBytes, { animated: true, limitInputPixels: false })
      .resize({ width: target.width, height: target.height, fit: "fill", kernel: sharp.kernel.lanczos3 })
      .gif({
        loop: metadata.loop ?? 1,
        delay: metadata.delay,
        colours: 64,
        dither: 0.5,
        effort: 10,
      })
      .toBuffer();
    if (/timer-slide-\d+\.gif$/.test(item.name)) {
      outputBytes = injectGifComment(outputBytes, `independent-${path.basename(item.name, ".gif")}`);
    }
  } else if (item.format === "jpeg") {
    outputBytes = await sharp(inputBytes)
      .resize({ width: target.width, height: target.height, fit: "fill", kernel: sharp.kernel.lanczos3, withoutEnlargement: true })
      .jpeg({ quality: 84, chromaSubsampling: "4:4:4", mozjpeg: true })
      .toBuffer();
  } else if (item.format === "png") {
    if (target.scale >= 0.999 && item.bytes < 100000) return { bytes: inputBytes, target, keptOriginal: true };
    outputBytes = await sharp(inputBytes)
      .resize({ width: target.width, height: target.height, fit: "fill", kernel: sharp.kernel.lanczos3, withoutEnlargement: true })
      .png({ compressionLevel: 9, adaptiveFiltering: true, palette: false })
      .toBuffer();
  } else {
    return { bytes: inputBytes, target, keptOriginal: true };
  }
  if (outputBytes.length >= inputBytes.length) return { bytes: inputBytes, target, keptOriginal: true };
  return { bytes: outputBytes, target, keptOriginal: false };
}

async function inspectWithArtifactTool() {
  const presentation = await PresentationFile.importPptx(await FileBlob.load(sourcePath));
  const snapshot = await presentation.inspect({
    kind: "deck,slide,image,layout",
    include: "id,slide,name,bbox,alt",
    maxChars: 20000,
  });
  await fs.writeFile(artifactInspectPath, snapshot.ndjson);
  if (presentation.slides.items.length !== 28) {
    throw new Error(`Artifact Tool imported ${presentation.slides.items.length} slides, expected 28`);
  }
}

async function main() {
  await inspectWithArtifactTool();
  const [sourceBytes, inventoryText] = await Promise.all([
    fs.readFile(sourcePath),
    fs.readFile(inventoryPath, "utf8"),
  ]);
  const inventory = JSON.parse(inventoryText);
  const zip = await JSZip.loadAsync(sourceBytes);
  const sourcePartNames = Object.keys(zip.files).filter((name) => !zip.files[name].dir);
  const sourcePartHashes = new Map();
  for (const name of sourcePartNames) {
    sourcePartHashes.set(name, sha256(await zip.file(name).async("nodebuffer")));
  }

  const changes = [];
  for (const item of inventory.media) {
    if (item.usages.length === 0) continue;
    const entry = zip.file(item.name);
    if (!entry) throw new Error(`Missing media part: ${item.name}`);
    const inputBytes = await entry.async("nodebuffer");
    const result = await optimizeMedia(item, inputBytes);
    zip.file(item.name, result.bytes, { binary: true, date: entry.date });
    const metadata = await sharp(result.bytes, { animated: true, limitInputPixels: false }).metadata();
    changes.push({
      name: item.name,
      inputBytes: inputBytes.length,
      outputBytes: result.bytes.length,
      savedBytes: inputBytes.length - result.bytes.length,
      reductionPercent: Number(((1 - result.bytes.length / inputBytes.length) * 100).toFixed(1)),
      inputDimensions: [item.width, item.height],
      outputDimensions: [metadata.width, metadata.pageHeight || metadata.height],
      frames: metadata.pages || 1,
      loop: metadata.loop ?? null,
      delayMin: metadata.delay ? Math.min(...metadata.delay) : null,
      delayMax: metadata.delay ? Math.max(...metadata.delay) : null,
      keptOriginal: result.keptOriginal,
    });
  }

  const legacyTimer = "ppt/media/image2.gif";
  const legacyReferences = [];
  for (const name of sourcePartNames.filter((name) => name.endsWith(".xml") || name.endsWith(".rels"))) {
    const text = await zip.file(name).async("string");
    if (text.includes("image2.gif")) legacyReferences.push(name);
  }
  if (legacyReferences.length > 0) {
    throw new Error(`Legacy timer is still referenced by: ${legacyReferences.join(", ")}`);
  }
  if (zip.file(legacyTimer)) zip.remove(legacyTimer);

  const finalBytes = await zip.generateAsync({
    type: "nodebuffer",
    compression: "DEFLATE",
    compressionOptions: { level: 9 },
    platform: "DOS",
  });
  await fs.writeFile(finalPath, finalBytes);

  const finalZip = await JSZip.loadAsync(finalBytes);
  const finalPartNames = Object.keys(finalZip.files).filter((name) => !finalZip.files[name].dir);
  const changedParts = new Set(changes.filter((item) => !item.keptOriginal).map((item) => item.name));
  const unexpectedContentChanges = [];
  for (const name of sourcePartNames) {
    if (name === legacyTimer || changedParts.has(name)) continue;
    const finalEntry = finalZip.file(name);
    if (!finalEntry) {
      unexpectedContentChanges.push({ name, issue: "missing" });
      continue;
    }
    const finalHash = sha256(await finalEntry.async("nodebuffer"));
    if (finalHash !== sourcePartHashes.get(name)) unexpectedContentChanges.push({ name, issue: "content changed" });
  }

  const timerChecks = [];
  for (const slide of [7, 9, 11, 13, 15, 17, 19, 21, 23, 25]) {
    const name = `ppt/media/timer-slide-${String(slide).padStart(2, "0")}.gif`;
    const bytes = await finalZip.file(name).async("nodebuffer");
    const metadata = await sharp(bytes, { animated: true, limitInputPixels: false }).metadata();
    timerChecks.push({
      slide,
      name,
      sha256: sha256(bytes),
      bytes: bytes.length,
      width: metadata.width,
      height: metadata.pageHeight,
      frames: metadata.pages,
      loop: metadata.loop,
      delayMin: Math.min(...metadata.delay),
      delayMax: Math.max(...metadata.delay),
    });
  }
  const uniqueTimerHashes = new Set(timerChecks.map((item) => item.sha256)).size;
  const sourceTimingParts = [];
  const finalTimingParts = [];
  for (let slide = 1; slide <= 28; slide += 1) {
    const name = `ppt/slides/slide${slide}.xml`;
    const sourceXml = await zip.file(name).async("string");
    const finalXml = await finalZip.file(name).async("string");
    if (sourceXml.includes("<p:timing")) sourceTimingParts.push(slide);
    if (finalXml.includes("<p:timing")) finalTimingParts.push(slide);
  }

  const audit = {
    sourcePath,
    finalPath,
    sourceBytes: sourceBytes.length,
    finalBytes: finalBytes.length,
    savedBytes: sourceBytes.length - finalBytes.length,
    reductionPercent: Number(((1 - finalBytes.length / sourceBytes.length) * 100).toFixed(1)),
    sourceSha256: sha256(sourceBytes),
    finalSha256: sha256(finalBytes),
    sourcePartCount: sourcePartNames.length,
    finalPartCount: finalPartNames.length,
    removedParts: [legacyTimer],
    changedMediaParts: [...changedParts].sort(),
    unexpectedContentChanges,
    sourceTimingParts,
    finalTimingParts,
    timerChecks,
    uniqueTimerHashes,
    changes,
  };
  if (unexpectedContentChanges.length > 0) throw new Error(`Unexpected package changes: ${JSON.stringify(unexpectedContentChanges)}`);
  if (uniqueTimerHashes !== 10) throw new Error(`Expected 10 unique timer GIF hashes, got ${uniqueTimerHashes}`);
  if (timerChecks.some((item) => item.frames !== 61 || item.loop !== 1 || item.delayMin !== 1000 || item.delayMax !== 1000)) {
    throw new Error("Timer frame timing changed during optimization");
  }
  if (JSON.stringify(sourceTimingParts) !== JSON.stringify(finalTimingParts)) throw new Error("Animation timing parts changed");
  await fs.writeFile(auditPath, JSON.stringify(audit, null, 2));
  console.log(JSON.stringify({
    finalPath,
    sourceBytes: audit.sourceBytes,
    finalBytes: audit.finalBytes,
    savedBytes: audit.savedBytes,
    reductionPercent: audit.reductionPercent,
    changedMediaParts: audit.changedMediaParts.length,
    removedParts: audit.removedParts,
    uniqueTimerHashes,
    timingParts: finalTimingParts,
  }, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
