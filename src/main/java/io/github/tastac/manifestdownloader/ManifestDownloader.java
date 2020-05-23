package io.github.tastac.manifestdownloader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ocelot.common.OnlineRequest;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ManifestDownloader extends Application {

    private static final File OUTPUT = new File("output");
    private AtomicInteger downloadCompleteCount = new AtomicInteger();
    private int total;

    private ProgressBar progressBar;
    private Text progressText;

    public static void main(String[] args) {
        Application.launch(args);
    }

    private void downloadGameFiles(){
        InputStream inputStream = ManifestDownloader.class.getResourceAsStream("/manifest.json");

        try(InputStreamReader inputStreamReader = new InputStreamReader(inputStream)){
            JsonObject json = new JsonParser().parse(inputStreamReader).getAsJsonObject().get("files").getAsJsonObject();

            total = json.size();
            for(Map.Entry<String, JsonElement> entry : json.entrySet()){
                if(!entry.getValue().isJsonObject()) continue;
                JsonObject entryObject = entry.getValue().getAsJsonObject();
                String type = entryObject.get("type").getAsString();

                if("directory".equalsIgnoreCase(type)){
                    createDirectory(entry.getKey());
                }else if("file".equalsIgnoreCase(type)){
                    String url = entryObject.get("downloads").getAsJsonObject().get("raw").getAsJsonObject().get("url").getAsString();
                    createFile(entry.getKey(), url);
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void createDirectory(String directory){
        initOutput();
        new File(OUTPUT, directory).mkdirs();
        updateProgress();
    }

    private void createFile(String fileName, String url){
        initOutput();
        File file = new File(OUTPUT, fileName);
        if(file.exists()){
            updateProgress();
            return;
        }
        OnlineRequest.make(url, inputStream -> {
            try {
                file.createNewFile();
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                IOUtils.copy(inputStream, fileOutputStream);
                fileOutputStream.close();
                updateProgress();
            }catch (IOException e){
                e.printStackTrace();
            }
        }, null);
    }

    private  void initOutput(){
        if(!OUTPUT.exists()) OUTPUT.mkdirs();
    }

    public void updateProgress(){
        downloadCompleteCount.incrementAndGet();
        progressText.setText(downloadCompleteCount.get() + "/" + total + " Files Downloaded");
        progressBar.setProgress(total == 0 ? 0 : (double)downloadCompleteCount.get() / (double)total);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Group root = new Group();
        Scene scene = new Scene(root, 640, 480);
        stage.setScene(scene);
        stage.setTitle("Mojang Manifest Downloader");
        stage.setResizable(false);

        progressText = new Text();
        progressText.setText(downloadCompleteCount.get() + "/" + total + " Files Downloaded");

        progressBar = new ProgressBar();
        progressBar.setProgress(total == 0 ? 0 : (double)downloadCompleteCount.get() / (double)total);
        progressBar.setPrefSize(250, 25);

        VBox vBox = new VBox();
        vBox.setSpacing(5);
        vBox.setAlignment(Pos.CENTER);
        vBox.getChildren().addAll(progressText, progressBar);
        scene.setRoot(vBox);
        stage.show();

        downloadGameFiles();
        System.exit(0);
    }
}
