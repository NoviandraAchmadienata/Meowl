/**
 * 🐱 Meowl VPS Middleware Server
 * Node.js + Express.js REST API for Audio File Uploads & Downloads
 */

const express = require('express');
const multer  = require('multer');
const fs      = require('fs');
const path    = require('path');
const cors    = require('cors');

const app  = express();
const PORT = process.env.PORT || 3000;
const UPLOAD_BASE_DIR = path.join(__dirname, 'uploads');

// Ensure base upload directory exists
if (!fs.existsSync(UPLOAD_BASE_DIR)) {
  fs.mkdirSync(UPLOAD_BASE_DIR, { recursive: true });
}

app.use(cors());
app.use(express.json());

// Configure Multer Storage for file uploads per target_id
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    const targetId = req.params.target_id || 'default';
    const targetDir = path.join(UPLOAD_BASE_DIR, targetId);
    if (!fs.existsSync(targetDir)) {
      fs.mkdirSync(targetDir, { recursive: true });
    }
    cb(null, targetDir);
  },
  filename: (req, file, cb) => {
    const timestamp = Math.floor(Date.now() / 1000);
    const filename = `baru_${timestamp}.wav`;
    cb(null, filename);
  }
});

const upload = multer({
  storage: storage,
  limits: { fileSize: 5 * 1024 * 1024 } // Max 5MB per WAV audio file
});

// ─── ROUTES ─────────────────────────────────────────────────────────────────

// Health Check
app.get('/status', (req, res) => {
  res.json({
    status: 'ONLINE',
    system: 'Meowl VPS Middleware',
    timestamp: new Date().toISOString(),
    uptime: process.uptime()
  });
});

// HTTP POST Upload Audio file for target_id
app.post('/upload/:target_id', (req, res) => {
  // Support both Multipart Form and raw Octet-Stream/WAV body
  const contentType = req.headers['content-type'] || '';
  const targetId = req.params.target_id;

  if (contentType.includes('multipart/form-data')) {
    upload.single('audio')(req, res, (err) => {
      if (err) return res.status(500).json({ error: err.message });
      console.log(`[POST /upload] File saved for target ${targetId}: ${req.file.filename}`);
      res.status(201).json({ success: true, file: req.file.filename });
    });
  } else {
    // Handle Raw Stream Upload
    const targetDir = path.join(UPLOAD_BASE_DIR, targetId);
    if (!fs.existsSync(targetDir)) fs.mkdirSync(targetDir, { recursive: true });

    const filename = `baru_${Math.floor(Date.now()/1000)}.wav`;
    const filePath = path.join(targetDir, filename);

    const writeStream = fs.createWriteStream(filePath);
    req.pipe(writeStream);

    writeStream.on('finish', () => {
      console.log(`[POST /upload raw] File saved for ${targetId}: ${filename}`);
      res.status(201).json({ success: true, file: filename });
    });

    writeStream.on('error', (err) => {
      res.status(500).json({ error: err.message });
    });
  }
});

// HTTP GET Download Audio file for my_id
app.get('/download/:my_id/:filename', (req, res) => {
  const { my_id, filename } = req.params;
  const filePath = path.join(UPLOAD_BASE_DIR, my_id, filename);

  if (!fs.existsSync(filePath)) {
    console.log(`[GET /download] File not found: ${filePath}`);
    return res.status(404).json({ error: 'File audio tidak ditemukan' });
  }

  console.log(`[GET /download] Streaming file to ${my_id}: ${filename}`);
  res.setHeader('Content-Type', 'audio/wav');
  const readStream = fs.createReadStream(filePath);
  readStream.pipe(res);
});

// List Files for my_id
app.get('/files/:my_id', (req, res) => {
  const my_id = req.params.my_id;
  const targetDir = path.join(UPLOAD_BASE_DIR, my_id);

  if (!fs.existsSync(targetDir)) {
    return res.json({ files: [] });
  }

  const files = fs.readdirSync(targetDir);
  res.json({ files: files });
});

// Start Server
app.listen(PORT, () => {
  console.log(`🚀 Meowl VPS Middleware Server running on port ${PORT}`);
  console.log(`📂 Upload directory: ${UPLOAD_BASE_DIR}`);
});
