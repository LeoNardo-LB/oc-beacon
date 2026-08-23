# ch06 · Hilt 依赖注入 —— Spring DI 的移动端表亲

> 状态：⬜ 待生成 ｜ 前置：ch02
> 本章解决：@Inject/@Module/@HiltViewModel 全家桶怎么串起来；编译期 DI 与 Spring 运行期 DI 的差异。
> 填充方式：对 AI 说「填充 docs/learning 的 ch06」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- Hilt vs Spring DI：编译期生成 vs 反射运行期；无 @ComponentScan 手动注册
- @Module + @Provides/@Binds：接口→实现绑定（对照 @Bean）
- @HiltViewModel + hiltViewModel()：VM 如何拿到依赖（对照 @Autowired 构造注入）
- 作用域注解：@Singleton/@ViewModelScoped 层级
- Qualifier 解决同类型多实现

## 本项目锚点（待填充时重新验证）
- `OpenCodeApp.kt:61` — @HiltAndroidApp 总开关
- `di/` — NetworkModule / ApiModule / CoroutinesModule / DomainModule / ToolCardModule
- `data/di/DatabaseModule.kt` — Room 依赖提供
- `ui/screens/sessions/SessionListRoute.kt` — hiltViewModel() 调用处（24 行短文件）

## 观察任务预告
- 从 ChatRepository 接口出发，追出它被注入的完整绑定链
- 数一数项目里有多少个 @Qualifier，各自区分什么
