package de.intranda.goobi.plugins;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import com.google.gson.Gson;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.metadaten.Image;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class ImageSelectionStepPlugin implements IStepPluginVersion2 {

    @Getter
    private String title = "intranda_step_image_selection";
    @Getter
    private Step step;
    @Getter
    private String value;
    @Getter
    private boolean allowTaskFinishButtons;
    private String returnPath;

    @Getter
    private String folder;

    private Process process;
    
    private static final String JSON_PATH = "/home/zehong/work/selected_images.json";

    private int currentIndex = 0;

    @Getter
    private List<Path> imagePaths = new ArrayList<Path>();

    @Getter
    private List<Image> images = new ArrayList<Image>();

    private List<Image> imagesFirst20 = new ArrayList<Image>();

    private List<Image> imagesToShow = new ArrayList<Image>();
    //    private List<Image> imagesSelected = new ArrayList<Image>();

    //    private HashSet<Integer> selectedIndices = new HashSet<>();
    private Map<Integer, Image> selectedImageMap = new TreeMap<>();

    private static Random rand = new Random();

    //    public List<String> getImages() {
    //        //        return images.stream().forEach(Path::toString).collect(Collectors.toList()l);
    //        return images.stream().map(Path::toString).collect(Collectors.toList());
    //    }


    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        value = myconfig.getString("value", "default value");
        folder = myconfig.getString("folder", "master");
        allowTaskFinishButtons = myconfig.getBoolean("allowTaskFinishButtons", false);
        log.info("ImageSelection step plugin initialized");
        process = this.step.getProzess();
        try {
            // get folderPath
            Path folderPath = initializeFolderPath(folder);
            //            Path folderPath = Path.of(process.getImagesDirectory()); // images
            //            Path folderPath = Path.of(process.getImagesTifDirectory(false)); // media
            //            Path folderPath = Path.of(process.getImagesOrigDirectory(true)); // master
            log.debug("folder path = " + folderPath);

            // initialize images
            //            imagePaths = StorageProvider.getInstance().listFiles(folderPath.toString());
            initializeImages(folderPath);
            
        } catch (IOException | SwapException | DAOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    private Path initializeFolderPath(String folderName) throws IOException, SwapException, DAOException {
        switch (folderName) {
            case "media":
                return Path.of(process.getImagesTifDirectory(false));
            case "master":
                return Path.of(process.getImagesOrigDirectory(true));
            default:
                return Path.of(process.getImagesDirectory());
        }
    }

    private void initializeImages(Path folderPath) throws IOException, SwapException, DAOException {
        imagePaths = StorageProvider.getInstance().listFiles(folderPath.toString());
        int order = 0;
        String imageFolderName = folderPath.getFileName().toString();
        Integer thumbnailSize = null;
        for (Path imagePath : imagePaths) {
            String fileName = imagePath.getFileName().toString();
            Image image = new Image(process, imageFolderName, fileName, order++, thumbnailSize);
            images.add(image);
        }
        int topIndex = Math.min(20, images.size());
        imagesFirst20 = images.subList(0, topIndex);

    }

    public void function1() {
        showFirstTwenty();
        readSelectedFromJson();
        showSelectedImages();
    }

    public void showFirstTwenty() {
        int topIndex = imagesFirst20.size();
        log.debug("The first " + topIndex + " imges in " + folder + " will be shown:");
        imagesToShow = new ArrayList<Image>(imagesFirst20);
        currentIndex = 20;
        showImages(imagesToShow);
    }

    private void readSelectedFromJson() {
        // initialize selectedImageMap if Json file is successfully read
        if (StorageProvider.getInstance().isFileExists(Path.of(JSON_PATH))) {
            String storedJson = "";
            try (InputStream fileStream = StorageProvider.getInstance().newInputStream(Path.of(JSON_PATH))) {
                storedJson = new BufferedReader(new InputStreamReader(fileStream)).lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            log.debug("storedProperty = " + storedJson);
            Gson gson = new Gson();
            Processproperty storedProperty = gson.fromJson(storedJson, Processproperty.class);
            String[] namesOfSelected = storedProperty.getWert().split(" ");
            if (namesOfSelected.length > 0) {
                initializeSelectedImageMap(namesOfSelected);
            }
        }
    }

    private void initializeSelectedImageMap(String[] names) {
        selectedImageMap = new TreeMap<>();
        int index;
        int lastIndex = 0; // index of the last found image
        for (String name : names) {
            index = getIndexOfImage(name, lastIndex);
            if (index < 0) {
                log.debug("The image " + name + " was not found. It is probably renamed or moved.");
                continue;
            }
            selectedImageMap.put(index, images.get(index));
            lastIndex = index;
        }
    }

    private int getIndexOfImage(String name) {
        return getIndexOfImage(name, 0);
    }

    private int getIndexOfImage(String name, int start) {
        for (int i = start; i < images.size(); ++i) {
            Image image = images.get(i);
            if (name.equals(image.getImageName())) {
                return i;
            }
        }
        return -1;
    }

    public void showTenMore() {
        int topIndex = Math.min(currentIndex + 10, images.size());

        if (currentIndex == topIndex) {
            log.debug("All images are already shown.");
        }
        List<Image> imagesAdded = images.subList(currentIndex, topIndex);

        for (Image image : imagesAdded) {
            imagesToShow.add(image);
        }

        currentIndex = topIndex;
        showImages(imagesAdded);
    }

    public void selectFiveRandomly() {
        int numberOfUnselected = getNumberOfUnselectedBelow(currentIndex);
        log.debug("number of unselected below " + currentIndex + " = " + numberOfUnselected);
        int topIndex = Math.min(5, numberOfUnselected);
        for (int i = 0; i < topIndex; ++i) {
            int n = getNextIndex();
            Image imageToAdd = imagesToShow.get(n);
            selectedImageMap.put(n, imageToAdd);
            //            imagesSelected.add(imageToAdd);
            log.debug("new image selected: " + imageToAdd.getImageName());
        }

        showSelectedImages();
    }

    private int getNumberOfUnselectedBelow(int index) {
        int n = index;
        for (int key : selectedImageMap.keySet()) {
            if (key >= index) {
                break;
            }
            --n;
        }
        return n;
    }

    private int getNextIndex() {
        int n;
        do {
            n = rand.nextInt(currentIndex);
        } while (selectedImageMap.containsKey(n));

        return n;
    }

    public void deselectOneRandomly() {
        int sizeOfSelected = selectedImageMap.size();
        if (sizeOfSelected == 0) {
            log.debug("There is no image selected yet!");
            return;
        }
        Integer[] indices = selectedImageMap.keySet().toArray(new Integer[sizeOfSelected]);
        int randomIndex = indices[rand.nextInt(sizeOfSelected)];
        Image deselected = selectedImageMap.remove(randomIndex);
        log.debug("Image deselected: " + deselected.getImageName());
        //        imagesSelected.remove(deselected);
        showSelectedImages();
    }

    public void saveToJson() {
        log.debug("Button 5 is clicked.");
        //        List<Processproperty> properties = process.getEigenschaftenList();
        //        for (Processproperty property : properties) {
        //            log.debug("property.toString() = " + property.toString());
        //            log.debug("property.getTitel() = " + property.getTitel());
        //            log.debug("property.getNormalizedTitle() = " + property.getNormalizedTitle());
        //            log.debug("property.getWert() = " + property.getWert());
        //            log.debug("property.getNormalizedValue() = " + property.getNormalizedValue());
        //            log.debug("property.getProcessId() = " + property.getProcessId());
        //            log.debug("property.getId() = " + property.getId());
        //        }
        Processproperty property = new Processproperty();
        //        property.setProzess(process);
        property.setProcessId(process.getId());
        property.setTitel("names of selected images");
        String namesCombined = combineNamesOfSelectedImages();
        property.setWert(namesCombined);
        Gson gson = new Gson();
        try (FileWriter writer = new FileWriter(JSON_PATH)) {
            gson.toJson(property, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String combineNamesOfSelectedImages() {
        StringBuilder sb = new StringBuilder();
        Collection<Image> selectedImages = selectedImageMap.values();
        for (Image image : selectedImages) {
            sb.append(image.getImageName());
            sb.append(" ");
        }
        return sb.length() > 1 ? sb.substring(0, sb.length() - 1).toString() : "";
    }

    private void showImages(List<Image> newImagesToShow) {
        log.debug("The number of new images to show is " + newImagesToShow.size());
        for (Image image : newImagesToShow) {
            log.debug(image.getImageName());
        }
        log.debug("The number of all images shown is " + currentIndex);
    }

    private void showSelectedImages() {
        Collection<Image> selectedImages = selectedImageMap.values();
        for (Image image : selectedImages) {
            log.debug(image.getImageName());
        }
        log.debug("The number of selected images is " + selectedImages.size());
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.FULL;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_image_selection.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        boolean successful = true;
        // your logic goes here

        log.info("ImageSelection step plugin executed");
        if (!successful) {
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }
}
