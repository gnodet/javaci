package app;

import api.Constants;
import api.Model;
import api.Service;
import impl.ServiceImpl;

public class Main {
    public static void main(String[] args) {
        Service<Model> service = new ServiceImpl();
        Model result = service.process(Constants.DEFAULT_NAME);
        System.out.println(result);
    }
}
