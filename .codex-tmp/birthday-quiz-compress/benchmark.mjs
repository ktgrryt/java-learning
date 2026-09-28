import fs from "node:fs/promises";
import path from "node:path";
import sharp from "sharp";

const inventoryPath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/media-inventory.json";
const extractedDir = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/extracted/ppt/media";
const outputDir = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/benchmark-assets";
const reportPath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/benchmark-report.json";
const densityMultiplier = 2;

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

async function optimize(item, inputPath, outputPath) {
  const target = targetDimensions(item);
  if (item.format === "gif") {
    const metadata = await sharp(inputPath, { animated: true, limitInputPixels: false }).metadata();
    await sharp(inputPath, { animated: true, limitInputPixels: false })
      .resize({ width: target.width, height: target.height, fit: "fill", kernel: sharp.kernel.lanczos3 })
      .gif({
        loop: metadata.loop ?? 1,
        delay: metadata.delay,
        colours: 64,
        dither: 0.5,
        effort: 10,
      })
      .toFile(outputPath);
  } else if (item.format === "jpeg") {
    await sharp(inputPath)
      .resize({ width: target.width, height: target.height, fit: "fill", kernel: sharp.kernel.lanczos3, withoutEnlargement: true })
      .jpeg({ quality: 84, chromaSubsampling: "4:4:4", mozjpeg: true })
      .toFile(outputPath);
  } else if (item.format === "png") {
    if (target.scale >= 0.999 && item.bytes < 100000) {
      await fs.copyFile(inputPath, outputPath);
    } else {
      await sharp(inputPath)
        .resize({ width: target.width, height: target.height, fit: "fill", kernel: sharp.kernel.lanczos3, withoutEnlargement: true })
        .png({ compressionLevel: 9, adaptiveFiltering: true, palette: false })
        .toFile(outputPath);
    }
  } else {
    await fs.copyFile(inputPath, outputPath);
  }
  return target;
}

async function main() {
  const inventory = JSON.parse(await fs.readFile(inventoryPath, "utf8"));
  await fs.mkdir(outputDir, { recursive: true });
  const report = [];
  for (const item of inventory.media) {
    if (item.usages.length === 0) continue;
    const basename = path.basename(item.name);
    const inputPath = path.join(extractedDir, basename);
    const outputPath = path.join(outputDir, basename);
    const target = await optimize(item, inputPath, outputPath);
    const outMeta = await sharp(outputPath, { animated: true, limitInputPixels: false }).metadata();
    const outputBytes = (await fs.stat(outputPath)).size;
    report.push({
      name: item.name,
      inputBytes: item.bytes,
      outputBytes,
      reductionPercent: Number(((1 - outputBytes / item.bytes) * 100).toFixed(1)),
      inputDimensions: [item.width, item.height],
      targetDimensions: [target.width, target.height],
      outputDimensions: [outMeta.width, outMeta.pageHeight || outMeta.height],
      frames: outMeta.pages || 1,
      loop: outMeta.loop ?? null,
      delayMin: outMeta.delay ? Math.min(...outMeta.delay) : null,
      delayMax: outMeta.delay ? Math.max(...outMeta.delay) : null,
    });
  }
  report.sort((a, b) => b.inputBytes - a.inputBytes);
  const summary = {
    inputBytes: report.reduce((sum, item) => sum + item.inputBytes, 0),
    outputBytes: report.reduce((sum, item) => sum + item.outputBytes, 0),
    unusedBytesRemovable: inventory.media.filter((item) => item.usages.length === 0).reduce((sum, item) => sum + item.bytes, 0),
    items: report,
  };
  await fs.writeFile(reportPath, JSON.stringify(summary, null, 2));
  console.log(JSON.stringify(summary, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
