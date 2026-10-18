# ============================================================
# 前端镜像：Node 构建静态文件 -> Nginx 托管
# ============================================================

# ---------- 第一阶段：构建 ----------
FROM node:20-alpine AS build
WORKDIR /app

# 换成国内源，否则 npm install 会很慢甚至超时
RUN npm config set registry https://registry.npmmirror.com

COPY opsai-web/package*.json ./
RUN npm install

COPY opsai-web/ ./
RUN npm run build

# ---------- 第二阶段：Nginx ----------
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY opsai-web/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
