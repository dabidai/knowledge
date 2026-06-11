FROM node:20-alpine AS build

WORKDIR /app

# 复制项目文件（不含 node_modules 和 package-lock.json）
COPY package.json tsconfig*.json vite.config.ts index.html ./
COPY src ./src
COPY public ./public

# 安装依赖
RUN npm install --registry=https://registry.npmmirror.com

# 构建
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx-frontend.conf /etc/nginx/conf.d/default.conf
EXPOSE 3000
