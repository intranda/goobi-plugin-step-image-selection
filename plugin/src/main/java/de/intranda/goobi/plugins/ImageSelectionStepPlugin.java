package de.intranda.goobi.plugins;

import java.io.IOException;
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

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.metadaten.Image;
import de.sub.goobi.persistence.managers.PropertyManager;
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
    private boolean allowTaskFinishButtons;
    private String returnPath;

    private String folderName;

    private Process process;
    
    private static final String PROPERTY_TITLE = "plugin_intranda_step_image_selection";

    private int currentIndex = 0;

    private List<Path> imagePaths = new ArrayList<>();

    private List<Image> images = new ArrayList<>();

    private List<Image> imagesFirstLoad = new ArrayList<>();

    @Getter
    private List<Image> imagesToShow = new ArrayList<>();
    //    private List<Image> imagesSelected = new ArrayList<>();

    //    private HashSet<Integer> selectedIndices = new HashSet<>();
    private Map<Integer, Image> selectedImageMap = new TreeMap<>();

    private int defaultNumberToLoad;
    private int defaultNumberToAdd;

    private int maxSelectionAllowed;
    private int minSelectionAllowed;

    private Processproperty property = null;

    private static Random rand = new Random();
    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

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
        folderName = myconfig.getString("folder", "master");

        defaultNumberToLoad = myconfig.getInt("defaultNumberToLoad", 20);
        defaultNumberToAdd = myconfig.getInt("defaultNumberToAdd", 10);

        maxSelectionAllowed = myconfig.getInt("max", 5);
        minSelectionAllowed = myconfig.getInt("min", 1);

        allowTaskFinishButtons = myconfig.getBoolean("allowTaskFinishButtons", false);
        log.info("ImageSelection step plugin initialized");
        process = this.step.getProzess();
        try {
            // get folderPath
            Path folderPath = initializeFolderPath(process, folderName);
            log.debug("folder path = " + folderPath);

            // initialize images
            initializeImages(folderPath);
            
        } catch (IOException | SwapException | DAOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    private Path initializeFolderPath(Process process, String folderName) throws IOException, SwapException, DAOException {
        String folder = process.getConfiguredImageFolder(folderName);
        if (StringUtils.isBlank(folder)) {
            log.debug("The folder configured as '" + folderName + "' does not exist yet.");
            return null;
        }
        return Path.of(folder);
    }

    private void initializeImages(Path folderPath) throws IOException, SwapException, DAOException {
        if (folderPath == null) {
            log.debug("There are no images available for NULL!");
            return;
        }
        imagePaths = storageProvider.listFiles(folderPath.toString());
        int order = 0;
        String imageFolderName = folderPath.getFileName().toString();
        Integer thumbnailSize = null;
        for (Path imagePath : imagePaths) {
            String fileName = imagePath.getFileName().toString();
            Image image = new Image(process, imageFolderName, fileName, order++, thumbnailSize);
            images.add(image);
        }
        int topIndex = Math.min(defaultNumberToLoad, images.size());
        imagesFirstLoad = images.subList(0, topIndex);
    }

    public void loadImages() {
        loadFirstImages();
        readSelectedFromJson();
        showSelectedImages();
    }

    public void loadFirstImages() {
        int topIndex = imagesFirstLoad.size();
        log.debug("The first " + topIndex + " imges in " + folderName + " will be shown:");
        imagesToShow = new ArrayList<>(imagesFirstLoad);
        currentIndex = topIndex;
        showImages(imagesToShow);
    }

    private void readSelectedFromJson() {
        selectedImageMap = new TreeMap<>();
        setUpProcesspropertyToSave(process.getId(), PROPERTY_TITLE);

        String values = property.getWert();
        if (StringUtils.isBlank(values) || values.length() <= 2) {
            // the property is empty
            return;
        }

        values = values.substring(1, values.length() - 1);
        String[] valuesList = values.split(",");
        String[] names = new String[valuesList.length];
        for (int i = 0; i < valuesList.length; ++i) {
            String value = valuesList[i];
            String[] valueParts = value.split(":");
            names[i] = valueParts[0].replace("\"", "");
        }
        initializeSelectedImageMap(names);
    }

    private void initializeSelectedImageMap(String[] names) {
        //        selectedImageMap = new TreeMap<>();
        int index;
        int lastIndex = -1; // index of the last found image
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
        return getIndexOfImage(name, -1);
    }

    private int getIndexOfImage(String name, int start) {
        for (int i = start + 1; i < images.size(); ++i) {
            Image image = images.get(i);
            if (name.equals(image.getImageName())) {
                return i;
            }
        }
        return -1;
    }

    public void loadMoreImages() {
        int topIndex = Math.min(currentIndex + defaultNumberToAdd, images.size());

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

    public boolean saveAsProperty() {
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
        if (selectedImageMap.size() < minSelectionAllowed) {
            log.debug("Cannot save property. At least " + minSelectionAllowed + " should be selected.");
            return false;
        }
        setUpProcesspropertyToSave(process.getId(), PROPERTY_TITLE);
        String jsonOfSelected = createJsonOfSelectedImages();
        property.setWert(jsonOfSelected);
        log.debug(jsonOfSelected);
        PropertyManager.saveProcessProperty(property);

        return true;
    }

    private void setUpProcesspropertyToSave(int processId, String title) {
        if (property != null) {
            // already initialized
            return;
        }
        List<Processproperty> props = PropertyManager.getProcessPropertiesForProcess(processId);
        for (Processproperty p : props) {
            if (title.equals(p.getTitel())) {
                property = p;
                return;
            }
        }
        // no such property exists yet, create a new one
        property = new Processproperty();
        property.setProcessId(processId);
        property.setTitel(title);
    }

    private String createJsonOfSelectedImages() {
        StringBuilder sb = new StringBuilder("{");
        Collection<Image> selectedImages = selectedImageMap.values();
        for (Image image : selectedImages) {
            sb.append("\"");
            sb.append(image.getImageName());
            sb.append("\":\"");
            sb.append(image.getUrl());
            sb.append("\",");
        }
        if (sb.length() > 1) {
            sb.deleteCharAt(sb.length() - 1);
        }
        sb.append("}");
        return sb.toString();
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

    public Collection<Image> getImagesSelected() {
        return selectedImageMap.values();
    }

    public void selectImage(String name, int startIndex) {
        if (selectedImageMap.size() == maxSelectionAllowed) {
            log.debug("Cannot select more since the maximum number allowed " + maxSelectionAllowed + " is already reached.");
            return;
        }
        int index = getIndexOfImage(name, startIndex - 1);
        Image image = images.get(index);
        if (!selectedImageMap.containsValue(image)) {
            selectedImageMap.put(index, image);
            log.debug("new image selected: " + name);
        }
    }

    public void deselectImage(String name) {
        int index = getIndexOfImage(name);
        Image deselected = selectedImageMap.remove(index);
        log.debug("Image deselected: " + deselected.getImageName());
        showSelectedImages();
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

        // start loading
        loadImages();

        log.info("ImageSelection step plugin executed");
        if (!successful) {
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }
}
