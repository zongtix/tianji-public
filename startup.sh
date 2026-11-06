#! /bin/sh
cd /usr/local/src/script || exit 1
BASE_PATH='/usr/local/src/jenkins/workspace/tjxt-dev-build'
PROJECT_NAME=""
PROJECT_PATH=''
CONTAINER_NAME=""
JAVA_OPTS="-Xms300m -Xmx300m"
PORT=8080
DEBUG_PORT=0
while getopts "c:n:d:p:o:a:" opt; do
    case $opt in
         c)
            CONTAINER_NAME=$OPTARG
          ;;
         n)
            PROJECT_NAME=$OPTARG
          ;;
         d)
            PROJECT_PATH=$OPTARG
          ;;
         p)
            PORT=$OPTARG
          ;;
         o)
            [ -n "$OPTARG" ] && JAVA_OPTS=$OPTARG
          ;;
         a)
            [ -n "$OPTARG" ] && DEBUG_PORT=$OPTARG
          ;;
         ?)
            echo "unkonw argument"
            exit 1
          ;;
    esac
done
if [ "$DEBUG_PORT" = "0" ]; then
  JAVA_OPTS=$JAVA_OPTS
else
  JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
fi
if [ -z "${NACOS_NAMESPACE:-}" ] || [ -z "${NACOS_SERVER_ADDR:-}" ]; then
  echo "Set NACOS_NAMESPACE and NACOS_SERVER_ADDR explicitly before deployment."
  exit 1
fi
set --
if [ "$PROJECT_NAME" = "tj-aigc" ]; then
  if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
    echo "Set DEEPSEEK_API_KEY in the deployment process environment before starting tj-aigc."
    exit 1
  fi
  # Docker reads the value from the parent environment; the command contains only its name.
  set -- --env DEEPSEEK_API_KEY
fi
IMAGE_NAME="${CONTAINER_NAME}:latest"
echo "copy xx.jar from ${BASE_PATH}/${PROJECT_PATH}"
rm -f app.jar
cp ${BASE_PATH}/${PROJECT_PATH}/target/${PROJECT_NAME}.jar ./app.jar ||  exit 1

echo "begin to build ${PROJECT_NAME} image ！！"

[ -n "`docker ps -a | grep ${CONTAINER_NAME}`" ] && docker rm -f ${CONTAINER_NAME}
[ -n "`docker images | grep ${CONTAINER_NAME}`" ] && docker rmi ${IMAGE_NAME}

docker build -t ${IMAGE_NAME} . || exit 1
echo "${PROJECT_NAME} image build success，java_opts = $JAVA_OPTS ！！^_^"

echo "begin to create container ${CONTAINER_NAME}，port: ${PORT} ！！"

if [ "$DEBUG_PORT" = "0" ]; then
  echo "run in normal mode"
  docker run -d --name ${CONTAINER_NAME} "$@" \
   -p "${PORT}:${PORT}" \
   -e JAVA_OPTS="${JAVA_OPTS}" \
   -e NACOS_NAMESPACE="${NACOS_NAMESPACE}" \
   -e NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR}" \
   --memory 300m --memory-swap -1 \
   --network heima-net ${IMAGE_NAME} \
  || exit 1
else
  echo "run in debug mode"
  docker run -d --name ${CONTAINER_NAME} "$@" \
   -p "${PORT}:${PORT}" \
   -p ${DEBUG_PORT}:5005 \
   -e JAVA_OPTS="${JAVA_OPTS}" \
   -e NACOS_NAMESPACE="${NACOS_NAMESPACE}" \
   -e NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR}" \
   --network heima-net ${IMAGE_NAME} \
  || exit 1
fi
echo "container is running now !! ^_^"
exit 0
