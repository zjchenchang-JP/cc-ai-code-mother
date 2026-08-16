// 使用 OpenAPI 工具（https://www.npmjs.com/package/@umijs/openapi），根据后端接口文档自动生成请求代码
// 运行方式：先启动后端，再执行 npm run openapi2ts
// 注意：openapi2ts 命令通过 default export 读取本配置
export default {
  requestLibPath: "import request from '@/request'",
  // 后端是 Spring Boot 3 + knife4j（OpenAPI 3），文档地址为 /v3/api-docs
  schemaPath: 'http://localhost:8123/api/v3/api-docs',
  serversPath: './src',
}
