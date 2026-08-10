#!/bin/bash

# =============================================================================
# shareKeys.sh — Configura acceso SSH sin contraseña a los ordenadores remotos
# =============================================================================
# Uso:
#   ./shareKeys.sh <usuario> <IP1> <IP2> [IP3 ...]
#
# Requisitos:
#   - ssh-keygen y ssh-copy-id disponibles
#   - Acceso con contraseña a los ordenadores remotos (solo la primera vez)
# =============================================================================

set -e

USUARIO=$1
shift
IPS=("$@")

# Validar parámetros de entrada al script 
if [ -z "$USUARIO" ] || [ ${#IPS[@]} -eq 0 ]; then
    echo "[ERROR] Uso: $0 [<usuario>] <IP1> <IP2> [IP3 ...]"
    echo "[ERROR] Ejemplo: $0 alumno 172.20.7.232 172.20.7.74 172.20.7.215"
    exit 1
fi

# Crear directorio de claves, si no existe
if [ ! -d "$HOME/.ssh" ]; then
    mkdir "$HOME/.ssh"
fi

cd "$HOME/.ssh"

# Generar nuevo par de claves RSA
echo "[SSH] Generando clave RSA..."
ssh-keygen -t rsa -b 4096 -f "$HOME/.ssh/id_rsa" -N ""
echo "[SSH] Clave generada en: $HOME/.ssh/"

# Copiar la clave pública generada a cada ordenador remoto
for IP in "${IPS[@]}"; do
    HOST="$USUARIO@$IP"
    echo "[SSH] Copiando clave a $USUARIO@$IP..."
    scp ~/.ssh/id_rsa.pub $HOST:pubkey.txt 
    ssh $HOST "mkdir ~/.ssh; chmod 700 .ssh; cat pubkey.txt >> ~/.ssh/authorized_keys; rm ~/pubkey.txt; chmod 600 ~/.ssh/*; exit"
    echo "[SSH] Acceso configurado para $USUARIO@$IP"
    echo ""
done

echo ""
echo "[SSH] === Configuración completada ==="
