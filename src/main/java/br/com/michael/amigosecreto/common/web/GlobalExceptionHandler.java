package br.com.michael.amigosecreto.common.web;

import br.com.michael.amigosecreto.common.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ModelAndView handleNotFound() {
        return errorView("error/404", HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(CsrfTokenException.class)
    public ModelAndView handleCsrfFailure() {
        return errorView("error/403", HttpStatus.FORBIDDEN);
    }

    private ModelAndView errorView(String viewName, HttpStatus status) {
        ModelAndView modelAndView = new ModelAndView(viewName);
        modelAndView.setStatus(status);
        return modelAndView;
    }
}
