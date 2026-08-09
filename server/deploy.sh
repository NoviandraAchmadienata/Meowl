#!/bin/bash
# ==============================================================================
# 🐱 MEOWL VPS UNIVERSAL AUTOMATED DEPLOYMENT SCRIPT
# Supports: AlmaLinux / Rocky Linux / CentOS / RHEL / Fedora AND Ubuntu / Debian
# ==============================================================================

set -e

# Detect if sudo is needed or available
if [ "$(id -u)" -eq 0 ]; then
    SUDO=""
elif command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
else
    SUDO=""
fi

echo "🐱 Starting Meowl VPS Setup..."

# Detect Package Manager (dnf vs yum vs apt)
if command -v dnf >/dev/null 2>&1; then
    PKG_MANAGER="dnf"
elif command -v yum >/dev/null 2>&1; then
    PKG_MANAGER="yum"
elif command -v apt >/dev/null 2>&1; then
    PKG_MANAGER="apt"
else
    echo "❌ Error: Could not detect package manager (apt, dnf, yum)."
    exit 1
fi

echo "📌 Detected Package Manager: $PKG_MANAGER"

if [ "$PKG_MANAGER" = "dnf" ] || [ "$PKG_MANAGER" = "yum" ]; then
    # ─── RHEL / AlmaLinux / Rocky Linux / CentOS / Fedora ──────────────────────
    echo "📦 Updating system & installing EPEL repository..."
    $SUDO $PKG_MANAGER install -y epel-release curl git gnupg tar || true

    echo "💚 Installing Node.js & npm..."
    if command -v dnf >/dev/null 2>&1; then
        $SUDO dnf module reset nodejs -y || true
        $SUDO dnf module enable nodejs:18 -y || true
    fi
    $SUDO $PKG_MANAGER install -y nodejs npm mosquitto mosquitto-clients || true

    # Fallback Node.js install via NodeSource if version is missing or too old
    if ! command -v node >/dev/null 2>&1; then
        curl -fsSL https://rpm.nodesource.com/setup_18.x | $SUDO bash -
        $SUDO $PKG_MANAGER install -y nodejs
    fi

    echo "⚡ Installing PM2 globally..."
    $SUDO npm install -g pm2

    echo "📡 Configuring Mosquitto MQTT Broker..."
    $SUDO mkdir -p /etc/mosquitto/conf.d
    cat << 'EOF' | $SUDO tee /etc/mosquitto/conf.d/meowl.conf
listener 1883
allow_anonymous true
log_dest stdout
log_type error
log_type warning
EOF

    # Include conf.d in main mosquitto.conf if not present
    if [ -f /etc/mosquitto/mosquitto.conf ] && ! grep -q "include_dir /etc/mosquitto/conf.d" /etc/mosquitto/mosquitto.conf; then
        echo "include_dir /etc/mosquitto/conf.d" | $SUDO tee -a /etc/mosquitto/mosquitto.conf
    fi

    $SUDO systemctl restart mosquitto 2>/dev/null || $SUDO service mosquitto restart 2>/dev/null || true
    $SUDO systemctl enable mosquitto 2>/dev/null || true

    echo "🛡️ Configuring Firewall (firewalld)..."
    if command -v firewall-cmd >/dev/null 2>&1; then
        $SUDO systemctl start firewalld || true
        $SUDO firewall-cmd --permanent --add-port=1883/tcp || true
        $SUDO firewall-cmd --permanent --add-port=3000/tcp || true
        $SUDO firewall-cmd --permanent --add-port=80/tcp || true
        $SUDO firewall-cmd --reload || true
    fi

else
    # ─── Ubuntu / Debian ──────────────────────────────────────────────────────
    echo "📦 Updating system packages..."
    $SUDO apt update -y && $SUDO apt upgrade -y
    $SUDO apt install -y curl gnupg git ufw
    curl -fsSL https://deb.nodesource.com/setup_18.x | $SUDO bash -
    $SUDO apt install -y nodejs mosquitto mosquitto-clients
    $SUDO npm install -g pm2

    echo "📡 Configuring Mosquitto MQTT Broker..."
    cat << 'EOF' | $SUDO tee /etc/mosquitto/conf.d/meowl.conf
listener 1883
allow_anonymous true
log_dest stdout
log_type error
log_type warning
EOF

    $SUDO systemctl restart mosquitto 2>/dev/null || $SUDO service mosquitto restart 2>/dev/null || true
    $SUDO systemctl enable mosquitto 2>/dev/null || true

    echo "🛡️ Configuring Firewall (UFW)..."
    if command -v ufw >/dev/null 2>&1; then
        $SUDO ufw allow 22/tcp || true
        $SUDO ufw allow 80/tcp || true
        $SUDO ufw allow 1883/tcp || true
        $SUDO ufw allow 3000/tcp || true
        echo "y" | $SUDO ufw enable || true
    fi
fi

# ─── Common Step: Install Node.js Server Dependencies & Start PM2 ────────────
echo "🚀 Installing Meowl Node.js dependencies..."
npm install

echo "⚡ Starting Meowl Backend Server via PM2..."
pm2 stop meowl-vps 2>/dev/null || true
pm2 start server.js --name "meowl-vps"
pm2 save
pm2 startup 2>/dev/null || true

echo "=============================================================================="
echo "✅ MEOWL VPS SETUP COMPLETE!"
echo "📡 MQTT Broker Running on:   mqtt://YOUR_SERVER_IP:1883"
echo "🌐 REST API Server Running on: http://YOUR_SERVER_IP:3000/status"
echo "=============================================================================="
