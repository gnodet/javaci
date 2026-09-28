package impl;

import api.Constants;
import api.Model;
import api.Service;
import api.ValidationException;

public class ServiceImpl implements Service<Model> {
    private final Helper helper = new Helper();

    @Override
    public Model process(String input) {
        String normalized = helper.normalize(input);
        Model model = new Model();
        model.setName(normalized);
        model.setValue(helper.computeValue(normalized));
        return model;
    }

    @Override
    public void validate(Model model) throws ValidationException {
        if (model.getName() == null || model.getName().length() > Constants.MAX_LENGTH) {
            throw new ValidationException("Invalid name: " + model.getName());
        }
    }
}
