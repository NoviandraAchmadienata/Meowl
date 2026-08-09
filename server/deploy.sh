#!/bin/bash
# ==============================================================================
# 🐱 MEOWL VPS BULLETPROOF UNIVERSAL DEPLOYMENT SCRIPT
# Supports: All Linux Distros, Root & Non-Root, cPanel/CloudLinux & Custom VPS
# ==============================================================================

set -e

echo "🐱 Starting Meowl VPS Setup..."

# Detect if sudo is needed or available
if [ "$(id -u)" -eq 0 ]; then
    SUDO=""
elif command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
else
    SUDO=""
fi

# 1. Detect Package Manager
PKG_MANAGER=""
if command -v dnf >/dev/null 2>&1 || [ -f /usr/bin/dnf ]; then
    PKG_MANAGER="dnf"
elif command -v yum >/dev/null 2>&1 || [ -f /usr/bin/yum ]; then
    PKG_MANAGER="yum"
elif command -v apt >/dev/null 2>&1 || [ -f /usr/bin/apt ] || [ -f /usr/bin/apt-get ]; then
    PKG_MANAGER="apt"
elif command -v microdnf >/dev/null 2>&1; then
    PKG_MANAGER="microdnf"
elif command -v apk >/dev/null 2>&1; then
    PKG_MANAGER="apk"
elif command -v pacman >/dev/null 2>&1; then
    PKG_MANAGER="pacman"
elif command -v zypper >/dev/null 2>&1; then
    PKG_MANAGER="zypper"
else
    PKG_MANAGER="custom"
fi

echo "📌 System Environment: Package Manager = [$PKG_MANAGER]"

# 2. Install Node.js & npm if not present
if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    echo "💚 Node.js not detected. Installing Node.js..."
    case "$PKG_MANAGER" in
        "dnf"|"yum")
            $SUDO $PKG_MANAGER install -y epel-release curl git tar || true
            $SUDO $PKG_MANAGER install -y nodejs npm || true
            ;;
        "apt")
            $SUDO apt update -y || true
            $SUDO apt install -y nodejs npm curl git || true
            ;;
        "apk")
            $SUDO apk add --no-cache nodejs npm git curl
            ;;
        "pacman")
            $SUDO pacman -Sy --noconfirm nodejs npm git curl
            ;;
        *)
            echo "📥 Downloading portable Node.js binaries via NVM..."
            curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.5/install.sh | bash || true
            export NVM_DIR="$HOME/.nvm"
            [ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
            nvm install 18 || nvm install node
            nvm use 18 || nvm use node
            ;;
    esac
else
    echo "✅ Node.js $(node -v) & npm $(npm -v) detected!"
fi

# 3. Install PM2 process manager
if ! command -v pm2 >/dev/null 2>&1; then
    echo "⚡ Installing PM2 globally..."
    npm install -g pm2 || $SUDO npm install -g pm2 || npm install pm2
fi

# 4. Install Mosquitto MQTT Broker (if package manager available & root)
if ! command -v mosquitto >/dev/null 2>&1; then
    echo "📡 Installing Mosquitto MQTT Broker..."
    case "$PKG_MANAGER" in
        "dnf"|"yum")
            $SUDO $PKG_MANAGER install -y mosquitto || true
            ;;
        "apt")
            $SUDO apt install -y mosquitto || true
            ;;
        "apk")
            $SUDO apk add --no-cache mosquitto || true
            ;;
    esac
fi

# 5. Configure Mosquitto if installed
if [ -d /etc/mosquitto ]; then
    $SUDO mkdir -p /etc/mosquitto/conf.d
    cat << 'EOF' | $SUDO tee /etc/mosquitto/conf.d/meowl.conf 2>/dev/null || true
listener 1883
allow_anonymous true
log_dest stdout
log_type error
log_type warning
EOF
    $SUDO systemctl restart mosquitto 2>/dev/null || $SUDO service mosquitto restart 2>/dev/null || true
fi

# 6. Install Server Node.js Dependencies
echo "🚀 Installing Meowl Node.js dependencies..."
npm install

# 7. Start Meowl Backend Server via PM2 or Node background process
echo "⚡ Starting Meowl Backend Server..."
if command -v pm2 >/dev/null 2>&1; then
    pm2 stop meowl-vps 2>/dev/null || true
    pm2 start server.js --name "meowl-vps"
    pm2 save 2>/dev/null || true
else
    npx pm2 stop meowl-vps 2>/dev/null || true
    npx pm2 start server.js --name "meowl-vps" || nohup node server.js > meowl.log 2>&1 &
fi

echo "=============================================================================="
echo "✅ MEOWL VPS SETUP COMPLETE!"
echo "📡 REST API Server Running on: http://YOUR_SERVER_IP:3000/status"
echo "=============================================================================="
