#!/bin/bash

# ===================================================================================
# deploy.sh — Despliega los servidores PBFT en las máquina remotas y lanza el cliente
# ===================================================================================
# Uso:
#   ./deploy.sh <usuario> <IP1:PUERTO:NUM_PROCESOS> [IP2:PUERTO:NUM_PROCESOS ...]
#
# Nota: la PRIMERA IP debe ser la del nodo local (donde se ejecuta este script).
# Nota: absténgase de emplear la refencia "localhost" o 127.0.0.1 para la primera IP. 
#
# Estructura esperada (depositada dentro de la misma carpeta que este script):
#   ./servidorPBFT.war
#   ./tomcat/
#   ./cliente-pbft-master/
# ===================================================================================

USUARIO="$1"
shift
NODOS_RAW=("$@")

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

WAR_NAME="servidorPBFT.war"
WAR_PATH="$SCRIPT_DIR/servidorPBFT.war"

TOMCAT_SRC="$SCRIPT_DIR/tomcat"
TOMCAT_LOCAL="$SCRIPT_DIR/tomcat_deploy"
TOMCAT_REMOTE="tomcat_deploy"

CLIENTE_DIR="$SCRIPT_DIR/cliente-pbft-master"
BIN_DIR="$CLIENTE_DIR/bin"
LIB_DIR="$CLIENTE_DIR/lib"

DEPLOY_NAME="servidorPBFT"

# Validar parámetros de entrada al script
if [ -z "$USUARIO" ] || [ ${#NODOS_RAW[@]} -eq 0 ]; then
    echo "[ERROR] Uso: $0 <usuario> <IP1:PUERTO:NUM_PROCESOS> [IP2:PUERTO:NUM_PROCESOS ...]"
    echo "[ERROR] Ejemplo: $0 i0919324 172.20.7.140:8080:2 172.20.7.249:8080:2 172.20.7.101:8080:2"
    exit 1
fi

[ -f "$WAR_PATH" ]   || { echo "[ERROR] No se encuentra $WAR_PATH"; exit 1; }
[ -d "$TOMCAT_SRC" ] || { echo "[ERROR] No se encuentra $TOMCAT_SRC"; exit 1; }
[ -d "$BIN_DIR" ]    || { echo "[ERROR] No se encuentra $BIN_DIR"; exit 1; }

# Extraer las IPs (primer campo de cada IP:PUERTO:NUM_PROCESOS)
IPS=()
for NODO in "${NODOS_RAW[@]}"; do
    IPS+=("$(echo "$NODO" | cut -d: -f1)")
done

# Guardar la primera IP como la local
IP_LOCAL="${IPS[0]}"

echo "[INFO] USUARIO=$USUARIO"
echo "[INFO] IP_LOCAL=$IP_LOCAL"
echo "[INFO] NODOS=${NODOS_RAW[@]}"
echo "[INFO] Validaciones de entrada superadas."
echo ""

# Función para el deploy remoto
deploy_remoto() {
    local IP="$1"
    local HOST="$USUARIO@$IP"

    echo "======================================================="
    echo " Nodo REMOTO: $IP"
    echo "======================================================="

    echo "[DEPLOY] Parando Tomcat..."
    ssh "$HOST" "pkill -f catalina 2>/dev/null; true; sleep 2; echo '[OK] Tomcat detenido'"

    echo "[DEPLOY] Limpiando instalación anterior..."
    ssh "$HOST" "rm -rf \$HOME/$TOMCAT_REMOTE && mkdir -p \$HOME/$TOMCAT_REMOTE && echo '[OK] Limpieza ejecutada'"

    echo "[DEPLOY] Copiando Tomcat..."
    tar -C "$TOMCAT_SRC" -cf - . | ssh "$HOST" "tar -C \$HOME/$TOMCAT_REMOTE -xf -" \
        && echo "[OK] Tomcat copiado" \
        || { echo "[ERROR] Falló la copia del Tomcat"; return 1; }

    echo "[DEPLOY] Copiando WAR..."
    ssh "$HOST" "mkdir -p \$HOME/$TOMCAT_REMOTE/webapps"
    scp -q "$WAR_PATH" "$HOST:$TOMCAT_REMOTE/webapps/$WAR_NAME" \
        && echo "[OK] WAR copiado"

    echo "[DEPLOY] Arrancando Tomcat..."
    ssh "$HOST" "
        chmod +x \$HOME/$TOMCAT_REMOTE/bin/*.sh
        nohup \$HOME/$TOMCAT_REMOTE/bin/startup.sh > /tmp/tomcat_$IP.log 2>&1
        sleep 4
        pgrep -f catalina > /dev/null \
            && echo '[OK] Tomcat arrancado en $IP' \
            || echo '[WARN] Tomcat no arrancó — revisa: ssh $HOST tail /tmp/tomcat_$IP.log'
    "
    echo ""
}

# Función para el deploy en local
deploy_local() {
    echo "======================================================="
    echo " Nodo LOCAL ($IP_LOCAL)"
    echo "======================================================="

    echo "[DEPLOY] Parando Tomcat local..."
    pkill -f catalina 2>/dev/null
    sleep 2

    echo "[DEPLOY] Limpiando instalación anterior..."
    rm -rf "$TOMCAT_LOCAL"
    mkdir -p "$TOMCAT_LOCAL"

    echo "[DEPLOY] Copiando Tomcat..."
    tar -C "$TOMCAT_SRC" -cf - . | tar -C "$TOMCAT_LOCAL" -xf - \
        && echo "[OK] Tomcat copiado" \
        || { echo "[ERROR] Falló la copia local"; return 1; }

    echo "[DEPLOY] Copiando WAR..."
    mkdir -p "$TOMCAT_LOCAL/webapps"
    cp "$WAR_PATH" "$TOMCAT_LOCAL/webapps/$WAR_NAME"
    echo "[OK] WAR copiado"

    echo "[DEPLOY] Arrancando Tomcat local..."
    chmod +x "$TOMCAT_LOCAL/bin/"*.sh
    "$TOMCAT_LOCAL/bin/startup.sh" > /tmp/tomcat_local.log 2>&1
    sleep 4
    if pgrep -f catalina > /dev/null; then
        echo "[OK] Tomcat local arrancado"
    else
        echo "[WARN] Tomcat local no arrancó — revisa: tail /tmp/tomcat_local.log"
    fi
    echo ""
}

# Desplegar el servidor en todos los nodos
for IP in "${IPS[@]}"; do
    if [ "$IP" = "$IP_LOCAL" ]; then
        deploy_local
    else
        deploy_remoto "$IP"
    fi
done

echo "======================================================="
echo " Despliegue completado"
echo "======================================================="
echo ""
echo "Servidores disponibles en:"
for IP in "${IPS[@]}"; do
    echo "  http://$IP:8080/$DEPLOY_NAME/api/servicio"
done
echo ""

# Introducir un pequeño tiempo de espera hasta que los servidores estén operativos
echo "[INFO] Esperando a que el WAR termine de desplegarse..."
sleep 5

# Lanzar cliente en el nodo local
CLASSPATH="$BIN_DIR"
for JAR in "$LIB_DIR"/*.jar; do
    [ -f "$JAR" ] && CLASSPATH="$CLASSPATH:$JAR"
done

# Generar configuración de entrada para el cliente
NODOS=$(IFS=','; echo "${NODOS_RAW[*]}")

echo "======================================================="
echo " Lanzando cliente PBFT"
echo " Nodos: $NODOS"
echo "======================================================="
echo ""

java -cp "$CLASSPATH" cliente.Cliente --nodos "$NODOS"
