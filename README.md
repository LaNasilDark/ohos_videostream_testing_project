# VLC-J 视频播放器示例

这是一个使用 Java Swing 和 [vlcj](https://github.com/caprica/vlcj) 库构建的简单视频播放器。

它演示了如何播放本地视频文件或网络流（如 TCP H264 流），并通过**捆绑（Bundling）VLC 运行时**实现了自包含（Self-contained）运行，用户无需在设备上预先安装 VLC。

## 功能特性

- **播放控制**: 支持播放、暂停和停止功能。
- **媒体源**: 可以播放本地文件路径或网络媒体资源定位符 (MRL)。
- **低延迟优化**: 为 TCP H264 流提供了缓存优化选项。
- **自包含**: 将 VLC 核心文件与应用程序打包在一起，无需外部依赖。

## 项目结构

```text
ohos_videostream_testing_project/
├── pom.xml                   # Maven 配置文件
├── README.md                 # 项目说明
├── VLC/                      # 捆绑的 VLC 运行时
│   ├── libvlc.dll
│   ├── libvlccore.dll
│   └── plugins/
└── src/
    └── main/
        └── java/
            └── com/
                └── example/
                    └── VlcjExample.java  # 主程序代码
```

## 如何运行

### 要求

- **Java Development Kit (JDK)**: 8 或更高版本。
- **Maven**: 用于构建项目。
- **架构匹配**: 捆绑的 VLC 文件架构（64位或32位）必须与运行程序的 Java (JVM) 架构完全一致。

### 从 IDE 运行

1. 克隆或下载项目。
2. 使用您喜欢的 IDE (如 VS Code, IntelliJ IDEA, Eclipse) 打开项目。
3. IDE 会自动识别为 Maven 项目并下载相关依赖。
4. 找到 `src/main/java/com/example/VlcjExample.java` 文件并直接运行 `main` 方法。

### 从命令行运行 (使用 Maven)

1. **编译和打包**:
   在项目根目录下打开终端，运行以下命令来创建一个包含所有依赖的可执行 JAR 文件。

   ```bash
   # 编译项目并打包成一个 fat-jar
   mvn clean compile assembly:single
   ```

   这会使用 `maven-assembly-plugin` 在 `target` 目录下生成一个类似 `ohos_videostream_testing_project-1.0-SNAPSHOT-jar-with-dependencies.jar` 的文件。

2. **运行程序**:
   确保 `VLC` 文件夹与生成的 JAR 文件位于同一目录，然后运行：

   ```bash
   # 将 'your-jar-file-name.jar' 替换为实际生成的文件名
   java -jar target/ohos_videostream_testing_project-1.0-SNAPSHOT-jar-with-dependencies.jar
   ```

## 注意事项

- 如果程序启动失败并提示 "VLC 目录未找到"，请确保 `VLC` 文件夹与您的 JAR 文件或类路径根目录位于同一位置。
- 如果遇到黑屏或无法播放，请检查 `VLC/plugins` 文件夹是否完整。
