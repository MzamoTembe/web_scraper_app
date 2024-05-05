package dev.mzamo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.AmazonSNSException;
import com.amazonaws.services.sns.model.PublishResult;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.HashMap;
import java.util.List;

public class ScraperHandler implements RequestHandler<ScheduledEvent, Void>{
    private final String snsTopicArn = System.getenv("TopicArn");
    private final String url = "https://istorepreowned.co.za";
    private final String item = "apple-watch";
    private final String itemType = "apple-watch-s8-cell-alum-45mm";
    private final String navigateToItemsPageSelector = "//a[contains(@class, 'product-item__action-button') and contains(@class, 'button')]";
    private final String inStockSelector = "//button[contains(@class, 'product-form__add-button') and contains(@class, 'button')]";

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("EVENT TYPE: " + event.getClass());

        try {
            WebClient webClient = new WebClient();
            webClient.getOptions().setCssEnabled(false);
            webClient.getOptions().setJavaScriptEnabled(false);

            var productCollectionUrl = this.url + "/collections/" + this.item;
            HtmlPage page = webClient.getPage(productCollectionUrl);
            List<HtmlAnchor> products = page.getByXPath(this.navigateToItemsPageSelector);

            if (products.isEmpty()){
                logger.log("We couldn't find the product at all on the website, perhaps it's been taken down.");
                return null;
            }

            HashMap<String, String> productTypesInStock = new HashMap<String, String>();

            for (HtmlAnchor product : products) {
                if (product.toString().contains(this.itemType)){
                    HtmlPage productPage = product.click();

                    var productTypeUrl = productPage.getUrl().toString() + ".json";
                    var productTypePage = webClient.getPage(productTypeUrl);
                    var productTypesPageInJSON = JsonParser.parseString(productTypePage.getWebResponse().getContentAsString()).getAsJsonObject();
                    var productTypes = productTypesPageInJSON.getAsJsonObject("product").getAsJsonArray("variants");

                    for (JsonElement productType : productTypes) {
                        var productVariantId = productType.getAsJsonObject().get("id").getAsString();
                        var variantUrl = this.url + "/products/" + this.itemType + "?variant=" + productVariantId;

                        HtmlPage variantPage = webClient.getPage(variantUrl);
                        HtmlButton inStockButton = variantPage.getFirstByXPath(this.inStockSelector);
                        var isStockBtnEnabled = inStockButton.isDisabled();

                        if (isStockBtnEnabled) {
                            productTypesInStock.put(productVariantId, variantUrl);
                        }
                    }
                }
            }

            if (productTypesInStock.isEmpty()){
                logger.log("We couldn't find any product types in stock... we will try again in x days");
            }
            else {
                logger.log("Found Item(s)!!! And publishing to SNS now.");

                StringBuilder snsMessageBuilder = new StringBuilder();
                productTypesInStock.forEach((productTypeId, productTypeUrl) -> {
                    snsMessageBuilder.append(productTypeId).append(productTypeUrl).append("\n");
                });

                var publishResult = publishTopic(this.snsTopicArn, snsMessageBuilder.toString());
                logger.log("MessageId: " + publishResult.getMessageId() + publishResult.toString());
            }

            webClient.close();
            return null;
        }
        catch (Exception e) {
            logger.log("Error occurred: " + e.getMessage());
            logger.log(ExceptionUtils.getStackTrace(e));
            return null;
        }
    }

    private PublishResult publishTopic(String snsTopicArn, String message) {
        try {
            AmazonSNS snsClient = AmazonSNSClientBuilder.defaultClient();
            return snsClient.publish(snsTopicArn, message);
        } catch (AmazonSNSException snsException) {
            throw new RuntimeException("Error publishing message to SNS topic: " + snsTopicArn, snsException);
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error publishing message to SNS topic: " + snsTopicArn, e);
        }
    }
}