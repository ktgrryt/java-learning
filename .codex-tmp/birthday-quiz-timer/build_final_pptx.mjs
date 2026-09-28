import fs from "node:fs/promises";
import crypto from "node:crypto";
import JSZip from "jszip";

const sourcePptx = "/Users/ryoto/Downloads/shohko_birthday_quiz_2026_large_text (1).pptx";
const finalPptx = "/Users/ryoto/code/java-learning/shohko_birthday_quiz_2026_timer_reset.pptx";
const timerDir = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/timer-assets";
const auditPath = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-timer/final-package-audit.json";
const questionSlides = [7, 9, 11, 13, 15, 17, 19, 21, 23, 25];

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function countOccurrences(text, fragment) {
  let count = 0;
  let cursor = 0;
  while (true) {
    const index = text.indexOf(fragment, cursor);
    if (index < 0) return count;
    count += 1;
    cursor = index + fragment.length;
  }
}

async function entryBytes(zip, entryName) {
  const entry = zip.file(entryName);
  if (!entry) throw new Error(`Missing package part: ${entryName}`);
  return entry.async("nodebuffer");
}

async function build() {
  const sourceBytes = await fs.readFile(sourcePptx);
  const sourceHashBefore = sha256(sourceBytes);
  const sourceZip = await JSZip.loadAsync(sourceBytes);
  const outputZip = await JSZip.loadAsync(sourceBytes);
  const modifiedParts = [];
  const addedParts = [];

  const contentTypes = (await entryBytes(sourceZip, "[Content_Types].xml")).toString("utf8");
  if (!contentTypes.includes('Extension="gif"') || !contentTypes.includes('ContentType="image/gif"')) {
    throw new Error("The source package has no default GIF content type.");
  }

  for (const slideNumber of questionSlides) {
    const relPart = `ppt/slides/_rels/slide${slideNumber}.xml.rels`;
    const originalRels = (await entryBytes(sourceZip, relPart)).toString("utf8");
    const oldTarget = "../media/image2.gif";
    if (countOccurrences(originalRels, oldTarget) !== 1) {
      throw new Error(`Expected one shared timer relationship in ${relPart}.`);
    }

    const mediaName = `timer-slide-${String(slideNumber).padStart(2, "0")}.gif`;
    const mediaPart = `ppt/media/${mediaName}`;
    const replacementRels = originalRels.replace(oldTarget, `../media/${mediaName}`);
    outputZip.file(relPart, replacementRels);
    modifiedParts.push(relPart);

    const timerBytes = await fs.readFile(`${timerDir}/${mediaName}`);
    outputZip.file(mediaPart, timerBytes, { binary: true, compression: "STORE" });
    addedParts.push({ slide: slideNumber, part: mediaPart, bytes: timerBytes.length, sha256: sha256(timerBytes) });
  }

  const outputBytes = await outputZip.generateAsync({
    type: "nodebuffer",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
    platform: "UNIX",
  });
  await fs.writeFile(finalPptx, outputBytes);

  const sourceHashAfter = sha256(await fs.readFile(sourcePptx));
  if (sourceHashAfter !== sourceHashBefore) throw new Error("The source presentation changed unexpectedly.");

  const finalBytes = await fs.readFile(finalPptx);
  const finalZip = await JSZip.loadAsync(finalBytes);
  const expectedModified = new Set(modifiedParts);
  const unexpectedDifferences = [];
  const missingSourceParts = [];

  for (const [entryName, sourceEntry] of Object.entries(sourceZip.files)) {
    if (sourceEntry.dir) continue;
    const finalEntry = finalZip.file(entryName);
    if (!finalEntry) {
      missingSourceParts.push(entryName);
      continue;
    }
    if (expectedModified.has(entryName)) continue;
    const original = await sourceEntry.async("nodebuffer");
    const final = await finalEntry.async("nodebuffer");
    if (!original.equals(final)) unexpectedDifferences.push(entryName);
  }

  if (missingSourceParts.length || unexpectedDifferences.length) {
    throw new Error(
      `Package fidelity failed. Missing: ${missingSourceParts.join(", ") || "none"}; changed: ${unexpectedDifferences.join(", ") || "none"}.`,
    );
  }

  const timerHashes = [];
  for (const slideNumber of questionSlides) {
    const relPart = `ppt/slides/_rels/slide${slideNumber}.xml.rels`;
    const mediaName = `timer-slide-${String(slideNumber).padStart(2, "0")}.gif`;
    const mediaPart = `ppt/media/${mediaName}`;
    const rels = (await entryBytes(finalZip, relPart)).toString("utf8");
    if (countOccurrences(rels, `../media/${mediaName}`) !== 1 || rels.includes("../media/image2.gif")) {
      throw new Error(`Timer relationship verification failed on slide ${slideNumber}.`);
    }
    const timerBytes = await entryBytes(finalZip, mediaPart);
    timerHashes.push(sha256(timerBytes));
  }

  if (new Set(timerHashes).size !== questionSlides.length) {
    throw new Error("Final timer media parts are not all byte-distinct.");
  }

  const audit = {
    sourcePptx,
    finalPptx,
    sourceSha256: sourceHashBefore,
    finalSha256: sha256(finalBytes),
    sourceBytes: sourceBytes.length,
    finalBytes: finalBytes.length,
    slideCount: questionSlides.length + 18,
    independentTimerCount: questionSlides.length,
    modifiedParts,
    addedParts,
    missingSourceParts,
    unexpectedDifferences,
    fidelityPass: true,
  };
  await fs.writeFile(auditPath, `${JSON.stringify(audit, null, 2)}\n`, "utf8");
  console.log(JSON.stringify({ finalPptx, auditPath, finalSha256: audit.finalSha256 }, null, 2));
}

build().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
