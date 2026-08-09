# 📐 Panduan Manufaktur: Desain Casing 3D & Custom PCB Meowl

![Visual Render Produk Meowl (Kawaii Squircle Cat Box)](file:///C:/Users/Lenovo/.gemini/antigravity/brain/6f3c620d-1ffa-4095-a96c-507e70433c9d/meowl_device_accurate_product_render_1786032359959.jpg)

Dokumen panduan teknis manufaktur fisik untuk **Meowl**, mencakup spesifikasi pemodelan 3D Casing (Autodesk Fusion 360 / STL) serta tata letak sirkuit terpadu **Custom PCB** (KiCad / EasyEDA).

---

## 🐱 1. Spesifikasi Desain Casing 3D (3D Printable Enclosure)

### 1.1 Geometri & Dimensi Utama
* **Bentuk Dasar:** *Squircle* (Kotak Bersudut Membulat Ekstrem) dengan proporsi ala konsol Tamagotchi.
* **Dimensi Luar:** **85 mm (Panjang) × 85 mm (Lebar) × 42 mm (Tinggi)**.
* **Radius Sudut Utama:** $R = 22\text{ mm}$.
* **Ketebalan Dinding (*Wall Thickness*):** $2.4\text{ mm}$ (3 perimeter shell untuk kekuatan mekanis).
* **Konstruksi 2-Bagian:**
  1. **Top Shell (Cangkang Atas):** Mengusung wajah kucing, telinga 3D, cutout OLED, dan **Tepat 1 Tombol Tengah Multifungsi**.
  2. **Bottom Shell (Cangkang Bawah):** Tempat dudukan PCB, kompartemen baterai LiPo, grill speaker, pinhole mic, dan port USB Type-C.

---

### 1.2 Clearance & Toleransi Fit (+0.2 mm)

| Fitur Eksterior | Ukuran Fisik Komponen | Toleransi Cutout Casing | Catatan Desain |
| :--- | :--- | :--- | :--- |
| **Window OLED** | $26.8\text{ mm} \times 14.8\text{ mm}$ | **$27.2\text{ mm} \times 15.2\text{ mm}$** | Dilapisi mika akrilik transparan $1.0\text{ mm}$ agar *flush* rata bodi. |
| **Port USB Type-C**| $9.0\text{ mm} \times 3.2\text{ mm}$ | **$10.0\text{ mm} \times 4.5\text{ mm}$** | Berada di punggung belakang bawah (Modul TP4056). |
| **Tombol PTT Samping**| Diameter $12.0\text{ mm}$ | **$12.4\text{ mm}$** | Bentuk *paw print* silikon pada sisi kanan. |
| **Tombol Tengah (1x)** | Diameter $6.0\text{ mm}$ | **$6.3\text{ mm}$** | **Tepat 1 tombol tunggal** di bawah akrilik layar OLED. |
| **Speaker Grill** | Slot $7 \text{ buah}$ | **Lebar $1.5\text{ mm}$, Panjang $16\text{ mm}$** | Di bagian dasar cangkang bawah. |
| **Mic Pinhole** | Mic INMP441 | **Diameter $2.0\text{ mm}$** | Diberi chamfer $45^\circ$ untuk penangkapan suara jernih. |

---

### 1.3 Dudukan Internal & Standoffs (M2 Threaded Inserts)
* **Dudukan PCB Utama:** 4x Pilar silindris ($D = 4.5\text{ mm}$, Tinggi $6\text{ mm}$) dengan *Brass Heat-Set Insert* **M2 × 4mm** di keempat sudut PCB.
* **Dudukan Layar OLED:** 4x Standoff M2 × 3mm pada cangkang atas untuk mengunci frame PCB OLED 0.96".
* **Kompartemen Baterai:** Kantong khusus $50\text{ mm} \times 34\text{ mm} \times 8\text{ mm}$ di bagian dasar casing dengan *EVA Foam* pelindung getaran speaker.

---

### 1.4 Parameter Slicing 3D Printing

```ini
[Slicer Configuration]
Filament_Material = PLA_Matte / PETG (Pastel Pink / Pastel Blue)
Layer_Height = 0.2 mm
Wall_Line_Count = 3
Infill_Density = 20%
Infill_Pattern = Gyroid
Print_Speed = 60 mm/s
Supports = Tree Support (Hanya pada bagian bawah telinga & ptt cutout)
Build_Plate_Adhesion = Brim (5mm)
```

---

## ⚡ 2. Skema & Layout Custom PCB (KiCad / EasyEDA)

Untuk kerapian dan kestabilan transmisi sinyal audio I2S, pembuatan PCB kustom berukuran **75 mm × 75 mm** sangat disarankan dibandingkan papan kabel *perfboard*.

```
                              [ SAKLAR POWER ]
                                     │
 [ BATERAI LiPo 3.7V ] ──> [ TP4056 CHARGER ] ──> [ ESP32 VIN (5V LDO) ]
                                                        │
                      ┌─────────────────────────────────┼─────────────────────────────────┐
                      ▼                                 ▼                                 ▼
             [ INMP441 MIC (I2S) ]            [ MAX98357A AMP (I2S) ]           [ OLED SSD1306 (I2C) ]
             • SCK: GPIO 14                   • BCLK: GPIO 26                   • SDA: GPIO 21
             • WS:  GPIO 15                   • LRC:  GPIO 25                   • SCL: GPIO 22
             • SD:  GPIO 32                   • DIN:  GPIO 33
```

---

### 2.1 Teknik Pengurangan Derau Audio (*Noise Reduction & Star Grounding*)

> [!IMPORTANT]
> Modul Wi-Fi ESP32 memancar dengan arus puncak (*peak current*) hingga 500mA yang dapat menimbulkan bunyi desis (*humming/buzzing*) pada speaker jika jalur ground tercampur.

1. **Star Ground Topology:** Memisahkan jalur **Power Ground (GND)** baterai/TP4056 dengan **Analog Ground (AGND)** amplifier MAX98357A. Kedua ground hanya bertemu di satu titik (*Single Net Tie*) dekat pin GND ESP32.
2. **Kapasitor Decoupling Decisive:**
   * Pasang kapasitor elektrolit **$220\ \mu\text{F} / 10\text{V}$** paralel dengan kapasitor keramik **$0.1\ \mu\text{F}$** persis di dekat pin `VIN` dan `GND` MAX98357A.
   * Pasang kapasitor **$10\ \mu\text{F}$** di dekat pin `VDD` INMP441 Mic.
3. **Trace Impedance Matching:** Jalur sinyal clock I2S (`SCK`, `WS`, `BCLK`, `LRC`) dibuat sejajar dengan lebar trace minimum $0.25\text{ mm}$ (10 mil) dan dijauhkan dari antena Wi-Fi ESP32 (jarak minimum 5 mm).

---

### 2.2 Daftar Pin Header & Komponen PCB (BOM Layer)

| Komponen | Reference PCB | Package / Footprint | Catatan Laying Out |
| :--- | :--- | :--- | :--- |
| **ESP32 WROOM Board** | `U1` | `MODULE_ESP32_DEVKIT_V1` | Posisi antena PCB mengarah keluar tepi papan. |
| **TP4056 Type-C** | `U2` | `MOD_TP4056_PROTECTION` | Port Type-C menempel rata pada tepi bawah PCB. |
| **INMP441 Module** | `U3` | `HEADER_1x6_P2.54mm` | Ditempatkan dekat lubang mic casing bawah. |
| **MAX98357A Module**| `U4` | `HEADER_1x5_P2.54mm` | Dekat terminal speaker out. |
| **MicroSD SPI Module**| `U5` | `MOD_MICROSD_SPI` | Diletakkan di bagian bawah PCB (Bottom Layer). |
| **Decoupling Caps** | `C1, C2, C3` | `C_0805_2012Metric` / Through Hole | $220\mu\text{F}$ Electrolytic + $0.1\mu\text{F}$ Ceramic. |
| **Slide Power Switch**| `SW1` | `SWITCH_SLIDE_SS-12D00` | Di tepi samping PCB. |
| **Terminal Speaker** | `J1` | `TERMINAL_BLOCK_2POS_P3.5mm` | Konektor ke Mini Speaker 3W. |

---

## 🛠️ 3. Berkas Output & Langkah Produksi Hardware

1. **Berkas CAD (Casing 3D):**
   * Simpan file 3D assembly sebagai `Meowl_Casing_Top.stl` dan `Meowl_Casing_Bottom.stl`.
   * Lakukan *test print* cangkang bawah terlebih dahulu untuk menguji ketepatan *standoffs* M2 dan port Type-C.
2. **Berkas Gerber (PCB Manufacturing):**
   * Export berkas **Gerber (RS-274X)** dan **Drill File (Excellon)** dari KiCad/EasyEDA.
   * Kirim ke pabrik pembuat PCB (seperti JLCPCB / PCBWay) dengan spesifikasi: 2 Layer, Ketebalan $1.6\thtext{ mm}$, Surface Finish HASL (Lead Free), Warna Masking Hijau / Merah Muda.
