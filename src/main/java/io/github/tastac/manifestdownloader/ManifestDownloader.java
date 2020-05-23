package io.github.tastac.manifestdownloader;

import com.google.common.collect.Queues;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ocelot.common.OnlineRequest;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ManifestDownloader extends Application implements Executor {

    private final AtomicInteger downloadCompleteCount = new AtomicInteger();
    private int total;

    private final Queue<Runnable> queueChunkTracking = Queues.newConcurrentLinkedQueue();

    private ProgressBar progressBar;
    private Text progressText;
    private Button startButton;

    public static void main(String[] args) {
        Application.launch(args);
    }

    private boolean downloadFile(String hash, File file) {
        if (file.exists()) {
            try {
                FileInputStream fileInputStream = new FileInputStream(file);
                String localHash = DigestUtils.sha1Hex(fileInputStream);
                fileInputStream.close();

                if (hash.equalsIgnoreCase(localHash)) {
                    return true;
                } else {
                    System.out.println("Local file '" + file.getName() + "' had a hash of '" + localHash + "' while the server returned '" + hash + "'. File being redownloaded.");
                    if (!file.delete())
                        throw new IOException("Failed to delete file");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private void downloadManifest(String manifestURL) {
        this.downloadCompleteCount.set(0);
        this.total = 0;
        OnlineRequest.make(manifestURL, inputStream -> {
            try (InputStreamReader inputStreamReader = new InputStreamReader(inputStream)) {

                JsonObject manifestsJson = new JsonParser().parse(inputStreamReader).getAsJsonObject();//.getAsJsonObject("manifest");

                if (manifestsJson.has("files")) {
                    complete();
                    return;
                }

                for (Map.Entry<String, JsonElement> entry : manifestsJson.entrySet()) {
                    if (!entry.getValue().isJsonArray())
                        continue;

                    File output = new File("data/" + entry.getKey());
                    for (JsonElement manifestElement : entry.getValue().getAsJsonArray()) {
                        if (!manifestElement.isJsonObject())
                            continue;

                        String hash = manifestElement.getAsJsonObject().getAsJsonObject("manifest").get("sha1").getAsString();
                        String url = manifestElement.getAsJsonObject().getAsJsonObject("manifest").get("url").getAsString();

                        File manifestFile = new File(output, "manifest.json");
                        if (!downloadFile(hash, manifestFile)) {
                            output.mkdirs();
                            this.execute(() -> OnlineRequest.make(url, manifestStream -> {
                                System.out.println("Downloaded manifest");
                                try (FileWriter fileWriter = new FileWriter(manifestFile)) {
                                    IOUtils.copy(manifestStream, fileWriter, StandardCharsets.UTF_8);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    System.out.println("Manifest download failed.");
                                }

                                try (FileInputStream fileInputStream = new FileInputStream(manifestFile)) {
                                    downloadGameFiles(output, fileInputStream);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }, Exception::printStackTrace));
                        } else {
                            this.execute(() -> {
                                try (FileInputStream fileInputStream = new FileInputStream(manifestFile)) {
                                    downloadGameFiles(output, fileInputStream);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            });
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                OnlineRequest.forceShutdown();
                complete();
            }
        }, e -> {
            e.printStackTrace();
            OnlineRequest.forceShutdown();
            complete();
        });
    }

    private void downloadGameFiles(File output, InputStream inputStream) {
        ExecutorService folderCreator = Executors.newSingleThreadExecutor();

        try (InputStreamReader inputStreamReader = new InputStreamReader(inputStream)) {
            JsonObject json = new JsonParser().parse(inputStreamReader).getAsJsonObject().getAsJsonObject("files");

            this.total += json.size();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject entryObject = entry.getValue().getAsJsonObject();
                String type = entryObject.get("type").getAsString();

                if ("directory".equalsIgnoreCase(type)) {
                    createDirectory(folderCreator, output, entry.getKey());
                } else if ("file".equalsIgnoreCase(type)) {
                    String hash = entryObject.getAsJsonObject("downloads").getAsJsonObject("raw").get("sha1").getAsString();
                    String url = entryObject.getAsJsonObject("downloads").getAsJsonObject("raw").get("url").getAsString();
                    createFile(folderCreator, output, entry.getKey(), hash, url);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        folderCreator.shutdown();
    }

    private void createDirectory(Executor executor, File output, String directory) {
        File file = new File(output, directory);
        if (file.exists()) {
            updateProgress();
            return;
        }
        CompletableFuture.runAsync(() -> {
            System.out.println("Creating directory '" + directory + "'");
            file.mkdirs();
            updateProgress();
        }, executor);
    }

    private void createFile(Executor executor, File output, String fileName, String hash, String url) {
        CompletableFuture.runAsync(() -> {
            output.mkdirs();
            File file = new File(output, fileName);
            if (downloadFile(hash, file)) {
                updateProgress();
                return;
            }

            OnlineRequest.make(url, inputStream -> {
                try {
                    if ((file.getParentFile() != null && !file.getParentFile().mkdirs()) && !file.createNewFile())
                        throw new IOException("Failed to create file at '" + fileName + "'");
                    System.out.println("Downloaded '" + fileName + "' from '" + url + "'");
                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    IOUtils.copy(inputStream, fileOutputStream);
                    fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                updateProgress();
            }, Exception::printStackTrace);
        }, executor);
    }

    private void complete() {
        this.execute(() -> {
            OnlineRequest.restart();
            startButton.setDisable(false);
        });
    }

    public void updateProgress() {
        this.execute(() -> {
            this.downloadCompleteCount.incrementAndGet();
            this.progressText.setText(this.downloadCompleteCount.get() + "/" + this.total + " Files Downloaded");
            this.progressBar.setProgress(total == 0 ? 0 : (double) this.downloadCompleteCount.get() / (double) this.total);
            if (this.downloadCompleteCount.get() >= this.total) {
                complete();
            }
        });
    }

    @Override
    public void start(Stage stage) throws Exception {
        stage.setOnCloseRequest(event -> OnlineRequest.shutdown());

        Group root = new Group();
        Scene scene = new Scene(root, 640, 480);
        stage.setScene(scene);
        stage.setTitle("Mojang Manifest Downloader");
        stage.setResizable(false);

        progressText = new Text();
        progressText.setText(downloadCompleteCount.get() + "/" + total + " Files Downloaded");

        progressBar = new ProgressBar();
        progressBar.setProgress(total == 0 ? 0 : (double) downloadCompleteCount.get() / (double) total);
        progressBar.setPrefSize(250, 25);

        TextField inputURL = new TextField();
        inputURL.setMaxWidth(640.0 / 2.0);
        inputURL.setText("Input metadata URL here");

        startButton = new Button();
        startButton.setText("Start!");
        startButton.setOnMouseClicked(event -> {
            downloadManifest(inputURL.getText());
            startButton.setDisable(true);
        });

        VBox vBox = new VBox();
        vBox.setSpacing(5);
        vBox.setAlignment(Pos.CENTER);
        vBox.getChildren().addAll(progressText, progressBar, inputURL, startButton);
        scene.setRoot(vBox);
        stage.show();

//        OnlineRequest.restart();
//        downloadManifest(test);

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                Runnable runnable;
                while ((runnable = ManifestDownloader.this.queueChunkTracking.poll()) != null) {
                    runnable.run();
                }
            }
        }.start();
    }

    @Override
    public void execute(Runnable command) {
        this.queueChunkTracking.add(command);
    }
}
