#!/usr/bin/env bash
# ============================================================================
# Script de ayuda para ATS Validator con contenedores
# Compatible con Docker y Podman
# ============================================================================

set -e

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Variables
IMAGE_NAME="ats-validator"
CONTAINER_NAME="ats-validator"
PORT="${PORT:-8080}"

# Detectar Docker o Podman
if command -v docker &> /dev/null; then
    CONTAINER_CMD="docker"
    COMPOSE_CMD="docker compose"
elif command -v podman &> /dev/null; then
    CONTAINER_CMD="podman"
    COMPOSE_CMD="podman-compose"
else
    echo -e "${RED}Error: Ni Docker ni Podman están instalados${NC}"
    exit 1
fi

echo -e "${BLUE}Usando: $CONTAINER_CMD${NC}"

# Funciones
show_help() {
    cat << EOF
${GREEN}ATS Validator - Script de Gestión de Contenedores${NC}

${YELLOW}Uso:${NC}
    ./run.sh [comando]

${YELLOW}Comandos disponibles:${NC}
    build       Construir la imagen del contenedor
    start       Iniciar el contenedor
    stop        Detener el contenedor
    restart     Reiniciar el contenedor
    logs        Ver logs en tiempo real
    status      Ver estado del contenedor
    health      Verificar health check
    shell       Abrir shell dentro del contenedor
    clean       Detener y eliminar contenedor
    cleanall    Eliminar contenedor e imagen
    test        Probar la API con un health check
    compose-up  Iniciar con docker-compose/podman-compose
    compose-down Detener con docker-compose/podman-compose

${YELLOW}Variables de entorno:${NC}
    PORT        Puerto a exponer (default: 8080)
    
${YELLOW}Ejemplos:${NC}
    ./run.sh build
    ./run.sh start
    PORT=8085 ./run.sh start
    ./run.sh logs
    ./run.sh test

EOF
}

build_image() {
    echo -e "${BLUE}Construyendo imagen ${IMAGE_NAME}...${NC}"
    $CONTAINER_CMD build -t ${IMAGE_NAME}:latest -f Containerfile .
    echo -e "${GREEN}✓ Imagen construida exitosamente${NC}"
}

start_container() {
    if $CONTAINER_CMD ps -a --format "{{.Names}}" | grep -q "^${CONTAINER_NAME}$"; then
        echo -e "${YELLOW}El contenedor ya existe, iniciándolo...${NC}"
        $CONTAINER_CMD start ${CONTAINER_NAME}
    else
        echo -e "${BLUE}Iniciando contenedor ${CONTAINER_NAME} en puerto ${PORT}...${NC}"
        $CONTAINER_CMD run -d \
            --name ${CONTAINER_NAME} \
            -p ${PORT}:8080 \
            -e JAVA_OPTS="-Xms256m -Xmx1024m" \
            --restart unless-stopped \
            ${IMAGE_NAME}:latest
    fi
    echo -e "${GREEN}✓ Contenedor iniciado${NC}"
    echo -e "${BLUE}Esperando que el servicio esté listo (puede tardar ~40 segundos)...${NC}"
    sleep 5
}

stop_container() {
    echo -e "${BLUE}Deteniendo contenedor...${NC}"
    $CONTAINER_CMD stop ${CONTAINER_NAME} 2>/dev/null || true
    echo -e "${GREEN}✓ Contenedor detenido${NC}"
}

restart_container() {
    echo -e "${BLUE}Reiniciando contenedor...${NC}"
    $CONTAINER_CMD restart ${CONTAINER_NAME}
    echo -e "${GREEN}✓ Contenedor reiniciado${NC}"
}

show_logs() {
    echo -e "${BLUE}Mostrando logs (Ctrl+C para salir)...${NC}"
    $CONTAINER_CMD logs -f ${CONTAINER_NAME}
}

show_status() {
    echo -e "${BLUE}Estado del contenedor:${NC}"
    $CONTAINER_CMD ps -a --filter "name=${CONTAINER_NAME}"
    echo ""
    if $CONTAINER_CMD ps --filter "name=${CONTAINER_NAME}" --format "{{.Names}}" | grep -q "^${CONTAINER_NAME}$"; then
        echo -e "${GREEN}Estado: EJECUTÁNDOSE${NC}"
        echo -e "${BLUE}Puerto: ${PORT}${NC}"
    else
        echo -e "${RED}Estado: DETENIDO${NC}"
    fi
}

check_health() {
    echo -e "${BLUE}Verificando health check...${NC}"
    if ! $CONTAINER_CMD ps --filter "name=${CONTAINER_NAME}" --format "{{.Names}}" | grep -q "^${CONTAINER_NAME}$"; then
        echo -e "${RED}✗ El contenedor no está ejecutándose${NC}"
        exit 1
    fi
    
    echo -e "${BLUE}Esperando respuesta...${NC}"
    for i in {1..10}; do
        if curl -s -f http://localhost:${PORT}/api/ats/health > /dev/null 2>&1; then
            RESPONSE=$(curl -s http://localhost:${PORT}/api/ats/health)
            echo -e "${GREEN}✓ Servicio saludable${NC}"
            echo -e "${BLUE}Respuesta:${NC} $RESPONSE"
            return 0
        fi
        echo -e "${YELLOW}Intento $i/10...${NC}"
        sleep 3
    done
    
    echo -e "${RED}✗ El servicio no responde${NC}"
    echo -e "${YELLOW}Revisa los logs con: ./run.sh logs${NC}"
    exit 1
}

open_shell() {
    echo -e "${BLUE}Abriendo shell en el contenedor...${NC}"
    $CONTAINER_CMD exec -it ${CONTAINER_NAME} /bin/bash || \
    $CONTAINER_CMD exec -it ${CONTAINER_NAME} /bin/sh
}

clean_container() {
    echo -e "${BLUE}Limpiando contenedor...${NC}"
    $CONTAINER_CMD stop ${CONTAINER_NAME} 2>/dev/null || true
    $CONTAINER_CMD rm ${CONTAINER_NAME} 2>/dev/null || true
    echo -e "${GREEN}✓ Contenedor eliminado${NC}"
}

clean_all() {
    echo -e "${BLUE}Limpiando contenedor e imagen...${NC}"
    clean_container
    $CONTAINER_CMD rmi ${IMAGE_NAME}:latest 2>/dev/null || true
    echo -e "${GREEN}✓ Contenedor e imagen eliminados${NC}"
}

test_api() {
    echo -e "${BLUE}Probando la API...${NC}"
    check_health
    echo ""
    echo -e "${YELLOW}Endpoints disponibles:${NC}"
    echo -e "  GET  http://localhost:${PORT}/api/ats/health"
    echo -e "  POST http://localhost:${PORT}/api/ats/validar"
    echo ""
    echo -e "${BLUE}Ejemplo de uso:${NC}"
    echo -e "  curl -X POST http://localhost:${PORT}/api/ats/validar \\"
    echo -e "       -H \"Content-Type: application/xml\" \\"
    echo -e "       --data-binary @mi-archivo-ats.xml"
}

compose_up() {
    echo -e "${BLUE}Iniciando con compose...${NC}"
    $COMPOSE_CMD up -d --build
    echo -e "${GREEN}✓ Servicio iniciado con compose${NC}"
}

compose_down() {
    echo -e "${BLUE}Deteniendo con compose...${NC}"
    $COMPOSE_CMD down
    echo -e "${GREEN}✓ Servicio detenido con compose${NC}"
}

# Main
case "${1:-}" in
    build)
        build_image
        ;;
    start)
        start_container
        ;;
    stop)
        stop_container
        ;;
    restart)
        restart_container
        ;;
    logs)
        show_logs
        ;;
    status)
        show_status
        ;;
    health)
        check_health
        ;;
    shell)
        open_shell
        ;;
    clean)
        clean_container
        ;;
    cleanall)
        clean_all
        ;;
    test)
        test_api
        ;;
    compose-up)
        compose_up
        ;;
    compose-down)
        compose_down
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        show_help
        exit 1
        ;;
esac
