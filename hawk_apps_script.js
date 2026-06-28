// ══════════════════════════════════════════════════════════════════
//  HAWK AI — Google Apps Script Proxy
//  Deploy this as a Web App in Google Apps Script:
//    1. Go to script.google.com → New project
//    2. Paste this entire file
//    3. Replace SECRET_TOKEN with any password you choose
//    4. Create a Google Sheet named "HawkMemory" (or any name)
//       — paste its ID into SHEETS_ID
//    5. Create a Google Drive folder for node trees
//       — paste its ID into DRIVE_FOLDER_ID
//    6. Click Deploy → New deployment → Web app
//       Execute as: Me | Access: Anyone
//    7. Copy the deployment URL → paste into HawkCloudConfig.SCRIPT_URL
//    8. Paste your token → HawkCloudConfig.SCRIPT_TOKEN
// ══════════════════════════════════════════════════════════════════

const SECRET_TOKEN   = "REPLACE_WITH_YOUR_TOKEN";   // choose any secret
const SHEETS_ID      = "REPLACE_WITH_SHEET_ID";     // Google Sheet ID
const DRIVE_FOLDER_ID = "REPLACE_WITH_FOLDER_ID";   // Google Drive folder ID
const MEMORY_SHEET   = "Memories";                   // sheet tab name
const LOG_FILE_NAME  = "hawk_task_log.txt";

// ── ENTRY POINT ───────────────────────────────────────────────────

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    if (data.token !== SECRET_TOKEN) {
      return respond({ status: "error", message: "Unauthorized" });
    }
    switch (data.action) {
      case "saveMemory":    return saveMemory(data);
      case "loadMemories":  return loadMemories(data);
      case "searchMemories":return searchMemories(data);
      case "deleteMemory":  return deleteMemory(data);
      case "uploadNodeTree":return uploadNodeTree(data);
      case "downloadNodeTree": return downloadNodeTree(data);
      case "logTask":       return logTask(data);
      default: return respond({ status: "error", message: "Unknown action: " + data.action });
    }
  } catch(err) {
    return respond({ status: "error", message: err.toString() });
  }
}

// ── MEMORY (Google Sheets) ────────────────────────────────────────

function getOrCreateSheet() {
  const ss = SpreadsheetApp.openById(SHEETS_ID);
  let sheet = ss.getSheetByName(MEMORY_SHEET);
  if (!sheet) {
    sheet = ss.insertSheet(MEMORY_SHEET);
    sheet.appendRow(["id", "content", "timestamp", "source", "useCount"]);
  }
  return sheet;
}

function saveMemory(data) {
  const sheet = getOrCreateSheet();
  // Check for duplicate id
  const rows = sheet.getDataRange().getValues();
  for (let i = 1; i < rows.length; i++) {
    if (rows[i][0] === data.id) {
      // Update existing
      sheet.getRange(i + 1, 2).setValue(data.content);
      sheet.getRange(i + 1, 3).setValue(data.timestamp);
      return respond({ status: "ok", action: "updated" });
    }
  }
  // Append new row
  sheet.appendRow([data.id, data.content, data.timestamp, data.source || "user", 0]);
  return respond({ status: "ok", action: "created" });
}

function loadMemories(data) {
  const sheet = getOrCreateSheet();
  const rows = sheet.getDataRange().getValues();
  const result = rows.slice(1); // skip header
  return respond({ status: "ok", rows: result });
}

function searchMemories(data) {
  const query = (data.query || "").toLowerCase();
  const words = query.split(/\W+/).filter(w => w.length > 2);
  const sheet = getOrCreateSheet();
  const rows = sheet.getDataRange().getValues().slice(1);
  const scored = rows.map(row => {
    const content = (row[1] || "").toLowerCase();
    let score = words.reduce((s, w) => s + (content.includes(w) ? 1 : 0), 0);
    return { row, score };
  }).filter(x => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 8)
    .map(x => x.row);
  return respond({ status: "ok", rows: scored });
}

function deleteMemory(data) {
  const sheet = getOrCreateSheet();
  const rows = sheet.getDataRange().getValues();
  for (let i = 1; i < rows.length; i++) {
    if (rows[i][0] === data.id) {
      sheet.deleteRow(i + 1);
      return respond({ status: "ok" });
    }
  }
  return respond({ status: "ok", note: "not found" });
}

// ── NODE TREE STORAGE (Google Drive) ─────────────────────────────

function uploadNodeTree(data) {
  const folder = DriveApp.getFolderById(DRIVE_FOLDER_ID);
  const fileName = "tree_" + data.taskId + "_step" + data.step + "_" + Date.now() + ".json";
  const file = folder.createFile(fileName, data.content, MimeType.PLAIN_TEXT);
  return respond({ status: "ok", fileId: file.getId(), fileName: fileName });
}

function downloadNodeTree(data) {
  try {
    const file = DriveApp.getFileById(data.fileId);
    const content = file.getBlob().getDataAsString();
    return respond({ status: "ok", content: content });
  } catch(e) {
    return respond({ status: "error", message: "File not found: " + data.fileId });
  }
}

// ── TASK LOG (Google Drive) ───────────────────────────────────────

function logTask(data) {
  const folder = DriveApp.getFolderById(DRIVE_FOLDER_ID);
  const files = folder.getFilesByName(LOG_FILE_NAME);
  const entry = "[" + new Date(data.timestamp).toISOString() + "]\n" + data.summary + "\n\n";
  if (files.hasNext()) {
    const file = files.next();
    const existing = file.getBlob().getDataAsString();
    file.setContent(existing + entry);
  } else {
    folder.createFile(LOG_FILE_NAME, entry, MimeType.PLAIN_TEXT);
  }
  return respond({ status: "ok" });
}

// ── UTIL ──────────────────────────────────────────────────────────

function respond(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
